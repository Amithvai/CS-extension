package com.sonzai

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class Sonzai : MainAPI() {

    override var mainUrl = "https://drama.sonzaix.indevs.in"
    override var name = "Sonzai"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
    )

    private companion object {
        private const val API_BASE = "https://drama.sonzaix.indevs.in/api"
        private const val API_TIMEOUT = 30_000L
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "Accept" to "application/json"
        )

        private val PROVIDERS = listOf(
            "dramabox", "melolo", "freereels", "flickreels", "dramawave",
            "dramanova", "reelshort", "netshort", "shortmax", "goodshort",
        )

        private val URL_KEYS = listOf(
            "videoPath", "video_path", "videoUrl", "video_url", "play_url", "playUrl",
            "main_url", "backup_url", "hls_url", "file_url", "m3u8_url", "source", "src", "url", "m3u8"
        )
        private val QUALITY_KEYS = listOf("quality", "definition", "format", "sharpnessName")
        private val LANG_KEYS = listOf("language", "lang", "langCode")
        private val SUB_URL_KEYS = listOf("subtitle_url", "subtitleUrl", "url", "subtitle", "vtt")

        private val mapper = ObjectMapper()
    }

    private suspend fun fetchText(url: String): String? = try {
        app.get(url, headers = HEADERS, timeout = API_TIMEOUT).text
    } catch (e: Exception) {
        logError(e)
        null
    }

    private fun catalogUrl(provider: String, kind: String, page: Int): String =
        "$API_BASE/catalog?provider=${enc(provider)}&kind=${enc(kind)}&page=$page"

    private fun detailUrl(provider: String, id: String): String =
        "$API_BASE/detail?provider=${enc(provider)}&id=${enc(id)}"

    private fun streamUrl(provider: String, id: String, ep: Int, chapterId: String?): String =
        buildString {
            append("$API_BASE/stream?provider=").append(enc(provider))
            append("&id=").append(enc(id))
            append("&ep=").append(ep)
            if (!chapterId.isNullOrBlank()) append("&chapterId=").append(enc(chapterId))
        }

    private fun mediaProxy(url: String): String =
        "$API_BASE/media?url=${enc(url)}"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun fetchCatalogList(provider: String, kind: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val text = fetchText(catalogUrl(provider, kind, page)) ?: return emptyList<SearchResponse>() to false
        if (text.contains("\"error\"")) return emptyList<SearchResponse>() to false
        val parsed = tryParseJson<CatalogResponse>(text) ?: return emptyList<SearchResponse>() to false
        val items = parsed.data.flatMap { it.books ?: emptyList() }.mapNotNull { it.toSearchResponse(provider) }
        val hasMore = when {
            provider == "flickreels" && kind == "popular" -> false
            text.contains("\"has_more\":false") || text.contains("\"has_more\": false") -> false
            text.contains("\"has_more\":true") || text.contains("\"has_more\": true") -> true
            else -> items.isNotEmpty()
        }
        return items to hasMore
    }

    private suspend fun fetchAllProviders(kind: String, page: Int): Pair<List<SearchResponse>, Boolean> = coroutineScope {
        val results = PROVIDERS.map { p -> async { runCatching { fetchCatalogList(p, kind, page) }.getOrNull() } }.awaitAll()
        val items = results.filterNotNull().flatMap { it.first }.distinctBy { it.url }
        items to results.filterNotNull().any { it.second }
    }

    private fun Book.toSearchResponse(provider: String): SearchResponse? {
        val id = drama_id?.takeIf { it.isNotBlank() } ?: return null
        val t = drama_name?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(t, "$mainUrl/$provider/detail/$id", TvType.TvSeries) {
            this.posterUrl = thumb_url
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (provider, kind) = request.data.split("/").let { it.getOrNull(0) to it.getOrNull(1) }
        return try {
            if (provider == "all") {
                val (items, hasMore) = fetchAllProviders(kind ?: "home", page)
                newHomePageResponse(request.name, items, hasNext = hasMore)
            } else {
                val (items, hasMore) = fetchCatalogList(provider ?: "", kind ?: "home", page)
                newHomePageResponse(request.name, items, hasNext = hasMore)
            }
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    override val mainPage = mainPageOf(
        "all/home" to "Semua - Beranda",
        "all/popular" to "Semua - Populer",
        "all/new" to "Semua - Terbaru",
        "dramabox/home" to "DramaBox - Beranda",
        "dramabox/new" to "DramaBox - Terbaru",
        "dramabox/popular" to "DramaBox - Terpopuler",
        "melolo/home" to "Melolo - Beranda",
        "melolo/new" to "Melolo - Terbaru",
        "melolo/popular" to "Melolo - Terpopuler",
        "freereels/home" to "FreeReels - Beranda",
        "freereels/new" to "FreeReels - Terbaru",
        "freereels/popular" to "FreeReels - Terpopuler",
        "flickreels/home" to "FlickReels - Beranda",
        "flickreels/new" to "FlickReels - Terbaru",
        "dramawave/home" to "DramaWave - Beranda",
        "dramawave/new" to "DramaWave - Terbaru",
        "dramawave/popular" to "DramaWave - Terpopuler",
        "dramanova/home" to "DramaNova - Beranda",
        "dramanova/new" to "DramaNova - Terbaru",
        "dramanova/popular" to "DramaNova - Terpopuler",
        "reelshort/home" to "ReelShort - Beranda",
        "reelshort/new" to "ReelShort - Terbaru",
        "reelshort/popular" to "ReelShort - Terpopuler",
        "netshort/home" to "NetShort - Beranda",
        "netshort/new" to "NetShort - Terbaru",
        "netshort/popular" to "NetShort - Terpopuler",
        "shortmax/home" to "ShortMax - Beranda",
        "shortmax/new" to "ShortMax - Terbaru",
        "shortmax/popular" to "ShortMax - Terpopuler",
        "goodshort/home" to "GoodShort - Beranda",
        "goodshort/new" to "GoodShort - Terbaru",
        "goodshort/popular" to "GoodShort - Terpopuler",
    )

    // ---------------- Load (generic, mengikuti episodeList/collectCatalog di web) ----------------

    private val TITLE_KEYS = listOf("drama_name", "dramaName", "bookName", "book_name", "title", "name")
    private val DESC_KEYS = listOf("description", "desc", "introduction", "synopsis", "abstract")
    private val COVER_KEYS = listOf("thumb_url", "thumbUrl", "cover", "coverUrl", "cover_url", "poster", "pic", "image", "bookDetailCover")
    private val COUNT_KEYS = listOf("episode_count", "episodeCount", "chapterCount", "totalChapterNum", "total", "count")
    private val EP_LIST_KEYS = listOf("video_list", "videoList", "list", "episodes", "episodeList", "episode_list", "chapterList", "chapter_list", "chapters")
    private val EP_NUM_KEYS = listOf("chapterIndex", "chapter_index", "episode_number", "episodeNo", "episodeNumber", "episode", "ep", "index")
    private val EP_ID_KEYS = listOf("chapterId", "chapter_id", "episode_id", "episodeId", "video_id", "id", "vid")
    private val EP_TITLE_KEYS = listOf("chapterName", "chapter_name", "episode_name", "episodeName", "title")

    private fun textIn(node: JsonNode?, keys: List<String>): String? {
        if (node == null || !node.isObject) return null
        for (k in keys) {
            val v = node.get(k) ?: continue
            if (v.isTextual || v.isNumber) {
                val s = v.asText().trim()
                if (s.isNotBlank()) return s
            }
        }
        return null
    }

    private fun intIn(node: JsonNode?, keys: List<String>): Int? {
        if (node == null || !node.isObject) return null
        for (k in keys) {
            val v = node.get(k) ?: continue
            val n = when {
                v.isNumber -> v.asInt()
                v.isTextual -> v.asText().trim().toIntOrNull()
                else -> null
            }
            if (n != null) return n
        }
        return null
    }

    /** Cari array episode di data (termasuk data.info.episode_list ala dramawave) */
    private fun findEpisodeArray(data: JsonNode?): JsonNode? {
        if (data == null || !data.isObject) return null
        val candidates = mutableListOf<JsonNode>()
        candidates.add(data)
        data.get("info")?.takeIf { it.isObject }?.let { candidates.add(it) }
        data.get("book")?.takeIf { it.isObject }?.let { candidates.add(it) }
        for (scope in candidates) {
            for (k in EP_LIST_KEYS) {
                val arr = scope.get(k)
                if (arr != null && arr.isArray && arr.size() > 0 && arr[0].isObject) return arr
            }
        }
        return null
    }

    private fun episodeNumberOf(item: JsonNode, index: Int, provider: String): Int {
        if (provider == "goodshort") {
            val name = textIn(item, EP_TITLE_KEYS) ?: ""
            Regex("(?:episode|ep)?\\s*0*(\\d+)", RegexOption.IGNORE_CASE).find(name)?.let {
                return it.groupValues[1].toIntOrNull() ?: (index + 1)
            }
        }
        val n = intIn(item, EP_NUM_KEYS)
        return if (n != null && n > 0) n else index + 1
    }

    override suspend fun load(url: String): LoadResponse {
        val path = url.removePrefix(mainUrl).trimStart('/').split('/').filter { it.isNotBlank() }
        val provider = path.getOrNull(0) ?: throw ErrorLoadingException("URL tidak valid")
        val id = path.getOrNull(2) ?: throw ErrorLoadingException("ID tidak valid")

        val text = fetchText(detailUrl(provider, id))
            ?: throw ErrorLoadingException("Gagal memuat detail")
        if (text.contains("\"error\"")) throw ErrorLoadingException("Detail tidak tersedia")
        val root = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            logError(e)
            throw ErrorLoadingException("Detail tidak valid")
        }
        val data = root.get("data")?.takeIf { it.isObject } ?: root
        val info = data.get("info")?.takeIf { it.isObject }
        val book = data.get("book")?.takeIf { it.isObject }

        val title = textIn(data, TITLE_KEYS)
            ?: textIn(book, TITLE_KEYS)
            ?: textIn(info, TITLE_KEYS)
            ?: "Drama"
        val poster = textIn(data, COVER_KEYS)
            ?: textIn(book, COVER_KEYS)
            ?: textIn(info, COVER_KEYS)
        val plot = textIn(data, DESC_KEYS)
            ?: textIn(book, DESC_KEYS)
            ?: textIn(info, DESC_KEYS)
        val total = intIn(data, COUNT_KEYS)
            ?: intIn(book, COUNT_KEYS)
            ?: intIn(info, COUNT_KEYS)
            ?: 0
        val tags = data.get("tags")?.takeIf { it.isArray }
            ?.mapNotNull { if (it.isTextual) it.asText() else null }
            ?.takeIf { it.isNotEmpty() }

        val epArr = findEpisodeArray(data)
        val episodes = mutableListOf<Episode>()
        val seenNumbers = mutableSetOf<Int>()
        epArr?.forEachIndexed { index, item ->
            val number = episodeNumberOf(item, index, provider)
            if (!seenNumbers.add(number)) return@forEachIndexed
            val chapterId = textIn(item, EP_ID_KEYS) ?: ""
            val epVal = if (provider == "dramabox") index else number
            episodes.add(newEpisode("$provider|$id|$epVal|$chapterId") {
                this.name = "Episode $number"
                this.episode = number
                this.posterUrl = textIn(item, COVER_KEYS) ?: poster
            })
        }
        // goodshort: API hanya mengembalikan sebagian chapter -> lengkapi sampai chapterCount
        if (provider == "goodshort" && total > 0) {
            for (n in 1..total) {
                if (seenNumbers.add(n)) {
                    episodes.add(newEpisode("$provider|$id|$n|") {
                        this.name = "Episode $n"
                        this.episode = n
                        this.posterUrl = poster
                    })
                }
            }
            episodes.sortBy { it.episode }
        }

        val finalEps = if (episodes.isNotEmpty()) {
            episodes
        } else {
            if (total <= 0) throw ErrorLoadingException("Episode tidak ditemukan")
            (1..total).map { n ->
                val epVal = if (provider == "dramabox") n - 1 else n
                newEpisode("$provider|$id|$epVal|") {
                    this.name = "Episode $n"
                    this.episode = n
                    this.posterUrl = poster
                }
            }
        }

        return if (finalEps.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, finalEps.firstOrNull()?.data ?: "$provider|$id|0|") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEps) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // ---------------- Stream (generic walker, mirip normalizeStream web) ----------------

    private data class FoundVideo(val url: String, val label: String)
    private data class FoundSub(val url: String, val lang: String, val label: String)

    private val QUALITY_CTX = Regex("quality|stream|video|play|cdn|resource", RegexOption.IGNORE_CASE)
    private val SUB_CTX = Regex("subtitle|caption|track", RegexOption.IGNORE_CASE)
    private val VIDEO_LEAF = Regex("quality|stream|video|play|cdn|resource|m3u8|hls", RegexOption.IGNORE_CASE)
    private val LABEL_HINT = Regex("h2?6[45]|m3u8|hls|\\d{3,4}|\\bhd\\b|\\bsd\\b|auto|default|backup|main", RegexOption.IGNORE_CASE)

    private fun tryAddVideo(node: JsonNode, videos: MutableList<FoundVideo>, fallbackLabel: String = "") {
        if (!node.isObject) return
        val url = textIn(node, URL_KEYS)?.takeIf { it.startsWith("http") } ?: return
        if (videos.any { it.url == url }) return
        videos.add(FoundVideo(url, textIn(node, QUALITY_KEYS) ?: fallbackLabel))
    }

    private fun walkStream(node: JsonNode, parentKey: String, inSub: Boolean, videos: MutableList<FoundVideo>, subs: MutableList<FoundSub>, depth: Int) {
        if (depth > 8 || node.isMissingNode) return
        if (node.isArray) {
            node.forEach { walkStream(it, parentKey, inSub, videos, subs, depth + 1) }
            return
        }
        if (!node.isObject) return
        if (!inSub && QUALITY_CTX.containsMatchIn(parentKey)) {
            tryAddVideo(node, videos)
        }
        if (SUB_CTX.containsMatchIn(parentKey)) {
            collectSubs(node, subs, depth + 1)
        }
        val fields = node.fieldNames()
        while (fields.hasNext()) {
            val key = fields.next()
            val value = node.get(key)
            val childInSub = inSub || SUB_CTX.containsMatchIn(key)
            if (value.isTextual) {
                val s = value.asText().trim()
                if (!childInSub && VIDEO_LEAF.containsMatchIn(key) &&
                    s.startsWith("http") && videos.none { it.url == s }
                ) {
                    val hint = if (LABEL_HINT.containsMatchIn(key)) key else ""
                    videos.add(FoundVideo(s, textIn(node, QUALITY_KEYS) ?: hint))
                }
            } else if (value != null && value.isContainerNode) {
                walkStream(value, key, childInSub, videos, subs, depth + 1)
            }
        }
    }

    private fun collectSubs(node: JsonNode, subs: MutableList<FoundSub>, depth: Int) {
        if (depth > 8) return
        if (node.isArray) {
            node.forEach { collectSubs(it, subs, depth + 1) }
            return
        }
        if (node.isObject) {
            val url = textIn(node, SUB_URL_KEYS)?.takeIf { it.startsWith("http") }
            if (url != null && subs.none { it.url == url }) {
                val lang = textIn(node, LANG_KEYS) ?: ""
                subs.add(FoundSub(url, lang, lang))
            }
            node.forEach { v -> if (v != null && v.isContainerNode) collectSubs(v, subs, depth + 1) }
        }
    }

    private fun parseQualityNumber(label: String?): Int {
        val n = Regex("\\d{3,4}").find(label ?: "")?.value
        return n?.toIntOrNull() ?: -1
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 3) return false
        val provider = parts[0]
        val id = parts[1]
        val ep = parts[2].toIntOrNull() ?: return false
        val chapterId = parts.getOrNull(3)?.takeIf { it.isNotBlank() }

        val text = fetchText(streamUrl(provider, id, ep, chapterId)) ?: return false
        if (text.contains("\"error\"")) return false
        val root = try {
            mapper.readTree(text)
        } catch (e: Exception) {
            logError(e)
            return false
        }

        val videos = mutableListOf<FoundVideo>()
        val subs = mutableListOf<FoundSub>()
        walkStream(root, "", false, videos, subs, 0)
        if (videos.isEmpty()) {
            val fallbackRoot = root.get("data")?.takeIf { it.isObject } ?: root
            tryAddVideo(fallbackRoot, videos)
        }

        subs.forEach { s ->
            val lang = s.lang.lowercase()
            if (lang.startsWith("id") || lang.startsWith("in")) {
                subtitleCallback(newSubtitleFile("Indonesia", mediaProxy(s.url)))
            }
        }

        var found = false
        val seen = mutableSetOf<String>()
        videos.forEach { v ->
            val isM3u8 = Regex("m3u8|hls", RegexOption.IGNORE_CASE).containsMatchIn(v.url)
            val quality = parseQualityNumber(v.label)
            val linkUrl = mediaProxy(v.url)
            if (seen.add(linkUrl)) {
                callback.invoke(newExtractorLink(
                    name,
                    "Sonzai ${v.label.takeIf { parseQualityNumber(it) > 0 } ?: ""}".trim(),
                    linkUrl,
                    if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                })
                found = true
            }
        }
        return found
    }

    // ---------------- Search (tidak ada endpoint search -> scan katalog populer) ----------------

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@coroutineScope emptyList()
        val jobs = PROVIDERS.map { provider ->
            async {
                try {
                    val kind = if (provider == "flickreels") "home" else "popular"
                    val (items, _) = fetchCatalogList(provider, kind, 1)
                    items.filter { it.name.lowercase().contains(q) }
                } catch (e: Exception) {
                    logError(e)
                    emptyList()
                }
            }
        }
        jobs.awaitAll().flatten().distinctBy { it.url }
    }

    // ---------------- Models ----------------

    data class CatalogResponse(
        val type: String? = null,
        val data: List<BookGroup> = emptyList(),
    )

    data class BookGroup(
        val books: List<Book>? = null,
    )

    data class Book(
        val drama_id: String? = null,
        val drama_name: String? = null,
        val description: String? = null,
        val episode_count: Int? = null,
        val thumb_url: String? = null,
        val tags: List<String>? = null,
    )
}