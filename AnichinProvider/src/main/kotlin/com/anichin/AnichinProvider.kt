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

        /**
         * Domain Anichin, urutan prioritas.
         *
         * PERUBAHAN 2026-10-23:
         * anichin.moe (situs utama, dibuka lewat landing page anichin.care)
         * sudah TIDAK lagi memblokir semua UA — sekarang hanya memblokir UA
         * non-browser (termasuk "okhttp/..." default OkHttp CloudStream) dengan
         * HTTP 403. Dengan UA browser, katalog / halaman / iframe / mirror
         * option semuanya ditembal HTTP 200.
         *
         * Konsekuensinya:
         *  - Setiap request ke host Anichin WAJIB menyertakan header UA browser
         *    (lihat SITE_HEADERS; dipakai di fetchDocument & loadAnichinPlayer).
         *  - anichin.moe kembali jadi domain utama; anichin.id tetap disimpan
         *    sebagai mirror fallback (tema animestream yang sama).
         */
        private val domains = listOf(
            "https://anichin.moe",
            "https://anichin.id",
        )

        /**
         * Path katalog per domain — struktur tiap mirror berbeda:
         *  - anichin.moe & anichin.id (theme animestream, sama):
         *    /anime/?status=...&order=update&page=N
         *    (status lowercase juga valid; query page di posisi mana pun tetap jalan)
         *  - jalur /ongoing/page/N/ & /completed/page/N/ dari theme themesia lama
         *    dipertahankan sebagai fallback terakhir (sekarang 301 → path baru,
         *    tapi masih valid bila suatu saat mirror mengganti tema lagi).
         *
         * Urutan query mengikuti persis link menu situs supaya paginasi
         * (?page=N&status=...) di-generate dengan format yang sama.
         */
        private val ONGOING_PATHS = listOf(
            "anime/?status=ongoing&page=%d",
            "anime/?status=Ongoing&type=&order=update&page=%d",
            "ongoing/page/%d/",
        )
        private val COMPLETED_PATHS = listOf(
            "anime/?status=completed&page=%d",
            "anime/?status=Completed&type=&order=update&page=%d",
            "completed/page/%d/",
        )
        private val LATEST_PATHS = listOf(
            "anime/?status=&type=&order=update&page=%d",
            "seri/?page=%d&status=&type=&order=",
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
            "anichin.site", "anichin.club", "anichin.stream"
        )

        /** UA dan header wajib untuk host Anichin — tanpa UA browser → HTTP 403 */
        private val SITE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private val SITE_HEADERS = mapOf("User-Agent" to SITE_UA)

        /**
         * Header untuk load gambar langsung dari host Anichin.
         * CATATAN: host Anichin menolak (403) request tanpa UA dan juga menolak
         * UA default OkHttp milik CloudStream. Karena itu gambar dialihkan lewat
         * proxy Jetpack Photon (lihat toProxiedPoster); header ini hanya dipakai
         * sebagai jalur alternatif bila proxy mati.
         */
        private val POSTER_HEADERS = mapOf(
            "Referer" to "https://anichin.moe/",
            "User-Agent" to SITE_UA
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

    // Path diisi per-request lewat mainPageData (lihat getMainPage) karena
    // tiap mirror memakai struktur path berbeda. Nilai di bawah hanya penanda.
    override val mainPage = mainPageOf(
        "LATEST" to "Latest",
        "ONGOING" to "Ongoing",
        "COMPLETED" to "Completed",
    )

    /**
     * Fetch dokumen dengan fallback multi-domain.
     * Jika host gagal (DNS/403/timeout), host akan di-mark dead 10 menit
     * lalu dicoba mirror berikutnya. mainUrl ikut di-update ke mirror yang hidup.
     *
     * Catatan: penggantian host memakai URI agar hanya bagian authority yang
     * berubah — replace() mentah bisa merusak path yang kebetulan memuat
     * string host.
     */
    private suspend fun fetchDocument(url: String, referer: String? = null): Document {
        val host = hostOf(url)
        if (!isDead(host)) {
            runCatching { app.get(url, headers = SITE_HEADERS, referer = referer, timeout = 15_000L).document }.getOrNull()
                ?.let { return it }
            markDead(host)
        }
        for (domain in domains) {
            val mirrorHost = hostOf(domain) ?: continue
            if (mirrorHost == host || isDead(mirrorHost)) continue
            val mirrorUrl = replaceHost(url, mirrorHost) ?: continue
            val doc = runCatching { app.get(mirrorUrl, headers = SITE_HEADERS, referer = referer, timeout = 15_000L).document }
                .getOrNull()
            if (doc != null) {
                mainUrl = domain
                return doc
            }
            markDead(mirrorHost)
        }
        throw ErrorLoadingException("Semua domain Anichin tidak dapat diakses")
    }

    /** Ganti hanya host pada URL, biarkan path/query utuh */
    private fun replaceHost(url: String, newHost: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: "https"
        URI(scheme, uri.userInfo, newHost, uri.port, uri.path, uri.query, uri.fragment).toString()
    }.getOrNull()

    /**
     * Normalisasi URL lama ke domain aktif — HANYA untuk keperluan navigasi
     * halaman (load/loadLinks), bukan untuk gambar (lihat toProxiedPoster).
     *
         * PENTING: hanya host yang ada di [KNOWN_HOSTS] yang diganti, dan hanya
         * bila target domain memang aktif. URL mirror lama dipetakan ke mainUrl
         * (anichin.moe) supaya halaman yang sama tetap terbuka di host aktif.
         */
    private fun normalizeUrl(url: String): String {
        val host = hostOf(url) ?: return url
        if (host in KNOWN_HOSTS && !url.startsWith(mainUrl)) {
            val newHost = hostOf(mainUrl) ?: return url
            return replaceHost(url, newHost) ?: url
        }
        return url
    }

    private fun Element.getImageAttr(): String? {
        // Ambil src mentah (bisa relatif) — prioritas data-src > src.
        // Atribut "abs:" tidak bisa diandalkan karena base URI dokumen tidak
        // selalu di-set CloudStream saat parsing, jadi di-resolve manual.
        val raw = when {
            hasAttr("data-src") -> attr("data-src")
            hasAttr("data-lazy-src") -> attr("data-lazy-src")
            hasAttr("srcset") -> attr("srcset").substringBefore(" ").trim()
            else -> attr("src")
        }
        return absolutize(raw)
    }

    /** Resolve URL gambar relatif (mis. "/wp-content/...") menjadi absolut */
    private fun absolutize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.startsWith("data:")) return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    /**
     * Alihkan URL gambar Anichin lewat proxy gambar publik.
     *
     * Kenapa WAJIB pakai proxy:
     *  - host Anichin MEMBLOKIR UA default OkHttp milik CloudStream (403),
     *    dan CloudStream tidak punya API untuk memaksa header pada image loader.
     *
     * PERUBAHAN 2026-10-23 (poster tidak muncul di halaman detail):
     *  - i0.wp.com (Photon) bertele-tele untuk gambar anichin.moe: sering balas
     *    400 "parameters not valid" walau gambarnya hidup (cold-cache / origin
     *      block yang tidak konsisten). Poster catalog yang pakai photon polos
     *    jadi kerap tidak muncul 1 dari 2 kali.
     *  - Jadi proxy UTAMA sekarang wsrv.nl (images.weserv.nl) — stabil 200
     *    untuk semua gambar yang dites. Photon tinggal cadangan.
     *
     * PENTING: host gambar TIDAK boleh dinormalisasi ke mainUrl. Setiap mirror
     * menyimpan file di host-nya masing-masing — memaksa path anichin.id ke
     * anichin.moe menghasilkan 301/400 (sudah diverifikasi). Jadi pakai host
     * asli dari URL gambar apa adanya.
     *
     * Hanya gambar dari host Anichin yang diproksikan; host lain (mis. TMDB)
     * dibiarkan apa adanya.
     */
    private fun toProxiedPoster(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val host = hostOf(value) ?: return value
        if (host !in KNOWN_HOSTS) return value
        // Pakai host asli gambar (jangan normalizeUrl) agar path tetap valid.
        return "https://wsrv.nl/?url=${URLEncoder.encode(value, "UTF-8")}"
    }

    /**
     * Varian cadangan: proxy Jetpack Photon (i0.wp.com).
     * Dipakai bila wsrv.nl sedang bermasalah.
     * Format: https://i0.wp.com/<host>/<path>
     */
    private fun toPhotonPoster(url: String?): String? {
        val value = url?.takeIf { it.isNotBlank() } ?: return null
        val host = hostOf(value) ?: return value
        if (host !in KNOWN_HOSTS) return value
        val withoutScheme = value.removePrefix("https://").removePrefix("http://")
        return "https://i0.wp.com/$withoutScheme"
    }

    /**
     * Pilih URL poster final: wsrv.nl dulu, Photon bila wsrv sedang bermasalah.
     *
     * Kedua proxy bisa lambat/404 sesaat, jadi masing-masing di-probe HEAD/GET
     * ringan (timeout pendek) sebelum dipakai. Bila keduanya gagal, tetap
     * kembalikan wsrv supaya ada kesempatan dirender (fallback terakhir).
     */
    private suspend fun resolvePoster(originalUrl: String?): String? {
        val original = originalUrl?.takeIf { it.isNotBlank() } ?: return null
        val host = hostOf(original) ?: return original
        if (host !in KNOWN_HOSTS) return original

        val wsrv = toProxiedPoster(original) ?: return original
        val photon = toPhotonPoster(original)

        val wsrvOk = runCatching {
            app.get(wsrv, timeout = 6_000L).isSuccessful
        }.getOrDefault(false)
        if (wsrvOk) return wsrv

        val photonOk = photon != null && runCatching {
            app.get(photon, timeout = 6_000L).isSuccessful
        }.getOrDefault(false)
        return if (photonOk) photon else wsrv
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
        val href = anchor.attr("href").ifBlank { anchor.attr("abs:href") }
        val title = anchor.attr("title").ifBlank {
            selectFirst("h2[itemprop=headline], .tt h2, .tt")?.text()?.trim().orEmpty()
        }
        if (title.isBlank() || href.isBlank()) return null

        val poster = toProxiedPoster(selectFirst("img")?.getImageAttr())
        val episode = selectFirst(".epx")?.text()?.replace(NON_DIGIT_REGEX, "")?.toIntOrNull()
        val type = getType(selectFirst(".typez")?.text())

        return newAnimeSearchResponse(title, fixUrl(href), type) {
            posterUrl = poster
            // Proxy Photon tidak butuh header; header tetap dipasang untuk
            // jaga-jaga bila suatu saat proxy dinonaktifkan.
            posterHeaders = POSTER_HEADERS
            addSub(episode)
        }
    }

    /**
     * Ambil daftar katalog. Karena tiap mirror memakai struktur path berbeda
     * (anichin.id: /anime/?status=... ; anichin.moe: /ongoing/page/N/), kita
     * coba semua varian path yang cocok untuk section ini sampai salah satu
     * menghasilkan artikel. Domain aktif (mainUrl) dipakai lebih dulu.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val candidates = when (request.data) {
            "ONGOING" -> ONGOING_PATHS
            "COMPLETED" -> COMPLETED_PATHS
            else -> LATEST_PATHS
        }

        for (pathTemplate in candidates) {
            val path = pathTemplate.replace("%d", page.toString())
            val document = runCatching { fetchDocument("$mainUrl/$path") }.getOrNull() ?: continue
            val results = document.select(".listupd article").mapNotNull { it.toSearchResult() }
            if (results.isNotEmpty()) {
                // Deteksi halaman berikutnya dari blok .hpage (theme animestream).
                // Link next selalu berformat "?page=<N+1>..." — kalau tidak ada
                // berarti ini halaman terakhir (mis. Completed yang cuma 1 halaman).
                val nextPage = page + 1
                val hasNext = document.select(".hpage a[href], .pagination a[href]")
                    .any { it.attr("href").contains("page=$nextPage") }
                return newHomePageResponse(
                    HomePageList(request.name, results),
                    hasNext = hasNext
                )
            }
        }
        return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
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
        val poster = resolvePoster(
            document.selectFirst(".thumb img, .bigcontent .thumb img")?.getImageAttr()
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )
        // Banner/background halaman detail: pakai poster yang sama bila tidak ada
        // gambar khusus (theme animestream tidak menyediakan fanart terpisah).
        // PERUBAHAN 2026-10-23: og:image anichin.moe sekarang path RELATIVE
        // ("/wp-content/...") — wajib di-absolutize, kalau tidak poster/banner
        // halaman detail tidak akan muncul.
        val backgroundPoster = resolvePoster(
            document.selectFirst("meta[property=og:image]")?.attr("content")?.let { absolutize(it) }
        ) ?: poster
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
            backgroundPosterUrl = backgroundPoster
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
     * Player Anichin yang dibungkus halaman perantara
     * (anichin-player.web.id/index.php?video=<id>) — dipakai anichin.moe.
     *
     * Strategi:
     *  1. Fetch halaman perantara (butuh Referer host Anichin, jika tidak → 403)
     *  2. Ambil src iframe (biasanya geo.dailymotion.com / ok.ru)
     *  3. Serahkan ke loadExtractor
     *  4. Fallback: rakit URL Dailymotion dari parameter ?video=
     *
     * Catatan: anichin.id TIDAK memakai perantara ini — iframe-nya sudah
     * langsung geo.dailymotion.com sehingga ditangani loadExtractor di
     * loadLinks tanpa melewati fungsi ini.
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
            app.get(url, headers = SITE_HEADERS, referer = "$mainUrl/", timeout = 15_000L).document
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
