package com.animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnimexinProvider : MainAPI() {
    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    private val cloudflareKiller by lazy { CloudflareKiller() }

    private val posterHeaders: Map<String, String>
        get() {
            val base = mutableMapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
                "Accept" to "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
            )
            runCatching { cloudflareKiller.getCookieHeaders(mainUrl).toMap() }
                .getOrNull()
                ?.forEach { (k, v) -> base[k] = v }
            return base
        }

    private fun Element?.getImageAttr(): String? {
        if (this == null) return null
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "data-lazy", "src")
        for (a in attrs) {
            val v = this.attr(a).trim()
            if (v.isNotEmpty() && !v.startsWith("data:")) return fixUrlNull(v)
        }
        val srcset = this.attr("data-srcset").ifBlank { this.attr("srcset") }.trim()
        if (srcset.isNotEmpty()) {
            val first = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")
            if (!first.isNullOrBlank() && !first.startsWith("data:")) return fixUrlNull(first)
        }
        return null
    }

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie" to "Movies",
        "anime/?sub=raw" to "Anime (RAW)",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}&page=$page", referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("div.bsx > a") ?: return null
        val title = a.attr("title").trim().ifEmpty { this.selectFirst("div.tt h2")?.text()?.trim().orEmpty() }
        val href = fixUrl(a.attr("href"))
        if (title.isEmpty() || href.isEmpty()) return null
        val posterUrl = a.selectFirst("img").getImageAttr()
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = this@AnimexinProvider.posterHeaders
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = app.get("$mainUrl/page/$page/?s=$query", referer = "$mainUrl/", interceptor = cloudflareKiller).document
        return document.select("div.listupd > article")
            .mapNotNull { it.toSearchResult() }
            .toNewSearchResponseList()
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val href = document.selectFirst("div.eplister > ul > li a")?.attr("href") ?: ""
        val poster = document.selectFirst("div.bigcontent div.thumb img").getImageAttr()
            ?: document.selectFirst("div.thumb img").getImageAttr()
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvtag = if (type.contains("Movie")) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val episodeRegex = Regex("""(\d+)""")

            val episodes = document.select("div.eplister > ul > li").map { info ->
                val episodeHref = info.select("a").attr("href")
                val episodePoster = info.selectFirst("a img").getImageAttr()

                val epText = info.selectFirst("div.epl-num")?.text().orEmpty()
                val epnum = episodeRegex.find(epText)?.groupValues?.get(1)?.toIntOrNull()

                newEpisode(episodeHref) {
                    this.episode = epnum
                    this.name = epnum?.let { "Episode $it" } ?: epText
                    this.posterUrl = fixUrlNull(episodePoster)
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed()) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@AnimexinProvider.posterHeaders
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, href) {
                this.posterUrl = fixUrlNull(poster)
                this.posterHeaders = this@AnimexinProvider.posterHeaders
                this.plot = description
            }
        }
    }

    /**
     * Halaman episode animexin.dev memuat daftar server di ".mobius option"
     * dengan value base64 yang berisi markup iframe player.
     *
     * Catatan 2026-10-24 (diverifikasi ke situs langsung):
     *  - Option pertama hanya label dropdown (value kosong/non-base64) -> decode
     *    dibungkus runCatching dan value kosong dilewati agar tidak crash.
     *  - Satu option bisa berisi >1 iframe -> semua iframe di-iterasi.
     *  - mega.nz sengaja dilewati (tidak ada extractor yang bisa memutar embed-nya).
     *  - animexinfansub.seekplayer.vip adalah SPA (player JS), belum ada
     *    extractor -> loadExtractor hanya no-op untuk URL itu.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/", interceptor = cloudflareKiller).document
        val processed = mutableSetOf<String>()
        document.select(".mobius option").forEach { server ->
            val base64 = server.attr("value").trim()
            if (base64.isBlank()) return@forEach
            val decoded = runCatching { base64Decode(base64) }.getOrNull() ?: return@forEach
            Jsoup.parse(decoded).select("iframe").forEach { iframe ->
                val url = Http(iframe.attr("src").trim())
                if (url.startsWith("http") && !url.contains("mega.nz") && processed.add(url)) {
                    loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
