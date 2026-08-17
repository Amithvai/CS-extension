package com.drachin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.net.URLEncoder

class Drachin : MainAPI() {

    override var mainUrl = "https://drama.sansekai.my.id"
    override var name = "Drachin"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    private companion object {
        private const val API_BASE = "https://api.sansekai.my.id/api"
        private const val API_TIMEOUT = 30_000L
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MIN_REQUEST_GAP_MS = 2_000L
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
            "Accept" to "application/json"
        )
        private val responseCache = mutableMapOf<String, Pair<Long, String>>()
        private val cacheLock = Any()
        private var lastRequestTime = 0L
        private val requestLock = Any()

        private fun isErrorResponse(text: String): Boolean {
            val t = text.trim()
            if (t.isBlank()) return true
            if (t.startsWith("[")) return false
            if (!t.startsWith("{")) return true
            return t.contains("\"error\"") || t.contains("Too Many Requests") ||
                t.contains("Forbidden") || t.contains("blacklist")
        }

        private fun cacheGet(url: String): String? = synchronized(cacheLock) {
            val cached = responseCache[url] ?: return@synchronized null
            if (System.currentTimeMillis() - cached.first > CACHE_TTL_MS) {
                responseCache.remove(url)
                null
            } else {
                cached.second
            }
        }

        private fun cachePut(url: String, body: String) = synchronized(cacheLock) {
            responseCache[url] = System.currentTimeMillis() to body
        }

        private suspend fun throttledFetch(url: String): String? {
            val wait = synchronized(requestLock) {
                val w = MIN_REQUEST_GAP_MS - (System.currentTimeMillis() - lastRequestTime)
                lastRequestTime = System.currentTimeMillis()
                w
            }
            if (wait > 0) delay(wait)
            return try {
                app.get(url, headers = HEADERS, timeout = API_TIMEOUT).text
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
    }

    private suspend fun fetchJson(url: String): String? {
        cacheGet(url)?.let { return it }
        var body = throttledFetch(url)
        if (body == null) return null
        if (isErrorResponse(body)) {
            // Rate limited / blacklisted: back off and retry once after the limit window
            delay(6_000L)
            body = throttledFetch(url)
            if (body == null || isErrorResponse(body)) return null
        }
        cachePut(url, body)
        return body
    }

    override val mainPage = mainPageOf(
        "dramabox/latest" to "DramaBox - Terbaru",
        "dramabox/trending" to "DramaBox - Terpopuler",
        "dramabox/dubindo" to "DramaBox - Dubindo",
        "dramabox/foryou" to "DramaBox - Lainnya",
        "pinedrama/trending" to "PineDrama - Trending",
        "pinedrama/foryou" to "PineDrama - Lainnya",
        "reelshort/foryou" to "ReelShort - For You",
        "melolo/latest" to "Melolo - Terbaru",
        "melolo/trending" to "Melolo - Trending",
        "melolo/foryou" to "Melolo - Lainnya",
        "freereels/foryou" to "FreeReels - For You",
        "dramanova/home" to "DramaNova - Home",
        "dramanova/drama18" to "DramaNova - 18+",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val parts = request.data.split("/")
            val platform = parts.getOrNull(0) ?: ""
            val section = parts.getOrNull(1) ?: ""
            val (items, hasNext) = fetchHome(platform, section, page)
            newHomePageResponse(request.name, items, hasNext = hasNext)
        } catch (e: Exception) {
            logError(e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    private suspend fun fetchHome(platform: String, section: String, page: Int): Pair<List<SearchResponse>, Boolean> {
        val items: List<SearchResponse>
        var hasNext = false
        when (platform) {
            "dramabox" -> {
                val url = when (section) {
                    "latest" -> "$API_BASE/dramabox/latest"
                    "trending" -> "$API_BASE/dramabox/trending"
                    "dubindo" -> "$API_BASE/dramabox/dubindo?classify=terbaru"
                    else -> "$API_BASE/dramabox/foryou?page=$page"
                }
                items = fetchDramaBoxList(url)
                hasNext = section == "foryou" && items.isNotEmpty()
            }
            "pinedrama" -> {
                val url = if (section == "trending") {
                    "$API_BASE/pinedrama/trending?cursor=1"
                } else {
                    "$API_BASE/pinedrama/foryou?cursor=$page"
                }
                items = fetchPineDramaList(url)
                hasNext = section == "foryou" && items.isNotEmpty()
            }
            "reelshort" -> {
                items = fetchReelShortList("$API_BASE/reelshort/foryou?page=$page")
                hasNext = items.isNotEmpty()
            }
            "melolo" -> {
                val url = when (section) {
                    "latest" -> "$API_BASE/melolo/latest"
                    "trending" -> "$API_BASE/melolo/trending"
                    else -> "$API_BASE/melolo/foryou?offset=${(page - 1) * 20}"
                }
                items = fetchMeloloList(url)
                hasNext = section == "foryou" && items.isNotEmpty()
            }
            "freereels" -> {
                items = fetchFreeReelsList("$API_BASE/freereels/foryou?offset=${(page - 1) * 20}")
                hasNext = items.isNotEmpty()
            }
            "dramanova" -> {
                items = if (section == "drama18") {
                    fetchDramaNovaDrama18("$API_BASE/dramanova/drama18?page=1")
                } else {
                    fetchDramaNovaList("$API_BASE/dramanova/home?page=$page")
                }
                hasNext = section == "home" && items.isNotEmpty()
            }
            else -> items = emptyList()
        }
        return items to hasNext
    }

    // ---------------- DramaBox ----------------

    private suspend fun fetchDramaBoxList(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val data = tryParseJson<Array<DramaBoxItem>>(text) ?: return emptyList()
        return data.mapNotNull { it.toSearchResponse() }
    }

    private fun DramaBoxItem.toSearchResponse(): SearchResponse? {
        val id = bookId ?: return null
        val title = bookName?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(title, "$mainUrl/dramabox/$id", TvType.TvSeries) {
            this.posterUrl = cover ?: coverWap
        }
    }

    private suspend fun loadDramaBox(url: String, id: String): LoadResponse {
        val detailText = fetchJson("$API_BASE/dramabox/detail?bookId=$id") ?: throw ErrorLoadingException("Gagal memuat detail DramaBox")
        val detail = tryParseJson<DramaBoxDetail>(detailText) ?: throw ErrorLoadingException("Detail DramaBox tidak valid")
        val title = detail.bookName?.takeIf { it.isNotBlank() } ?: "Drama"
        val poster = detail.coverWap
        val epsText = fetchJson("$API_BASE/dramabox/allepisode?bookId=$id")
        val episodes = tryParseJson<Array<DramaBoxEpisode>>(epsText ?: "")?.toList() ?: emptyList()

        val epList = if (episodes.isNotEmpty()) {
            episodes.mapIndexedNotNull { index, ep ->
                newEpisode("$mainUrl/dramabox/ep/$id/$index") {
                    this.name = ep.chapterName ?: "Episode ${index + 1}"
                    this.episode = index + 1
                    this.posterUrl = ep.chapterImg ?: poster
                }
            }
        } else {
            val total = detail.chapterCount ?: 0
            if (total <= 1) return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = detail.introduction
                this.tags = detail.tags
            }
            (1..total).mapNotNull { n ->
                newEpisode("$mainUrl/dramabox/ep/$id/${n - 1}") {
                    this.name = "Episode $n"
                    this.episode = n
                    this.posterUrl = poster
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, epList) {
            this.posterUrl = poster
            this.plot = detail.introduction
            this.tags = detail.tags
        }
    }

    private suspend fun loadDramaBoxLinks(
        id: String,
        index: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = fetchJson("$API_BASE/dramabox/allepisode?bookId=$id") ?: return false
        val episodes = tryParseJson<Array<DramaBoxEpisode>>(text) ?: return false
        val ep = episodes.getOrNull(index) ?: return false

        var found = false
        val defaultCdn = ep.cdnList?.firstOrNull { it.isDefault == 1 } ?: ep.cdnList?.firstOrNull()
        defaultCdn?.videoPathList?.forEach { vp ->
            val path = vp.videoPath ?: return@forEach
            if (vp.isVipEquity == 1) return@forEach
            val decrypted = "$API_BASE/dramabox/decrypt-stream?url=${URLEncoder.encode(path, "UTF-8")}"
            callback.invoke(newExtractorLink("DramaBox", "DramaBox ${vp.quality ?: ""}p", decrypted, ExtractorLinkType.VIDEO) {
                this.quality = vp.quality ?: -1
                this.referer = "$mainUrl/"
            })
            found = true
        }

        if (ep.useMultiSubtitle == 1) {
            val indo = ep.subLanguageVoList?.firstOrNull { it.captionLanguage == "in" }
                ?: ep.subLanguageVoList?.firstOrNull { it.isDefault == 1 }
            indo?.url?.let { subtitleCallback(newSubtitleFile("Indonesia", it)) }
        }
        return found
    }

    // ---------------- PineDrama ----------------

    private suspend fun fetchPineDramaList(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val data = tryParseJson<PineDramaResponse>(text) ?: return emptyList()
        return data.collections.mapNotNull { it.toSearchResponse() }
    }

    private fun PineDramaCollection.toSearchResponse(): SearchResponse? {
        val id = collectionId ?: return null
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(t, "$mainUrl/pinedrama/$id", TvType.TvSeries) {
            this.posterUrl = cover
        }
    }

    private suspend fun loadPineDrama(url: String, id: String): LoadResponse {
        val text = fetchJson("$API_BASE/pinedrama/detail?collection_id=$id") ?: throw ErrorLoadingException("Gagal memuat detail PineDrama")
        val detail = tryParseJson<PineDramaDetail>(text) ?: throw ErrorLoadingException("Detail PineDrama tidak valid")
        val title = detail.title?.takeIf { it.isNotBlank() } ?: "Drama"
        val poster = detail.coverUrls?.firstOrNull()
        val total = detail.total_episodes ?: 0
        val episodes = (1..total).mapNotNull { n ->
            newEpisode("$mainUrl/pinedrama/ep/$id/$n") {
                this.name = "Episode $n"
                this.episode = n
                this.posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = detail.description
        }
    }

    private suspend fun loadPineDramaLinks(
        id: String,
        episodeNumber: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = fetchJson("$API_BASE/pinedrama/episode?collection_id=$id&episodeNumber=$episodeNumber") ?: return false
        val ep = tryParseJson<PineDramaEpisode>(text) ?: return false
        val urls = buildList {
            ep.main?.indoHdCdnUrls?.forEach { if (it.isNotBlank()) add(it) }
            ep.main?.indoCdnUrls?.forEach { if (it.isNotBlank()) add(it) }
            ep.main?.cdnUrls?.forEach { if (it.isNotBlank()) add(it) }
            ep.bestUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.distinct()

        urls.forEach { u ->
            callback.invoke(newExtractorLink("PineDrama", "PineDrama", u, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(ep.quality ?: "")
                this.referer = "$mainUrl/"
            })
        }
        return urls.isNotEmpty()
    }

    // ---------------- ReelShort ----------------

    private suspend fun fetchReelShortList(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val data = tryParseJson<ReelShortResponse>(text) ?: return emptyList()
        return (data.data?.lists ?: emptyList()).mapNotNull { it.toSearchResponse() }
    }

    private fun ReelShortBook.toSearchResponse(): SearchResponse? {
        val id = bookId ?: return null
        val t = bookTitle?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(t, "$mainUrl/reelshort/$id", TvType.TvSeries) {
            this.posterUrl = bookPic
        }
    }

    private suspend fun loadReelShort(url: String, id: String): LoadResponse {
        val text = fetchJson("$API_BASE/reelshort/detail?bookId=$id") ?: throw ErrorLoadingException("Gagal memuat detail ReelShort")
        val detail = tryParseJson<ReelShortDetail>(text) ?: throw ErrorLoadingException("Detail ReelShort tidak valid")
        val title = detail.title?.takeIf { it.isNotBlank() } ?: "Drama"
        val chapters = detail.chapters ?: emptyList()
        val episodes = chapters.mapNotNull { ch ->
            val num = ch.index ?: return@mapNotNull null
            newEpisode("$mainUrl/reelshort/ep/$id/$num") {
                this.name = ch.title ?: "Episode $num"
                this.episode = num
                this.posterUrl = detail.cover
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = detail.cover
            this.plot = detail.description
        }
    }

    private suspend fun loadReelShortLinks(
        id: String,
        episodeNumber: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = fetchJson("$API_BASE/reelshort/episode?bookId=$id&episodeNumber=$episodeNumber") ?: return false
        val ep = tryParseJson<ReelShortEpisode>(text) ?: return false
        val h264 = ep.videoList?.filter { it.encode.equals("H264", ignoreCase = true) }
        val videos = h264?.ifEmpty { ep.videoList ?: emptyList() } ?: ep.videoList ?: emptyList()

        videos.forEach { v ->
            val u = v.url ?: return@forEach
            callback.invoke(newExtractorLink("ReelShort", "ReelShort ${v.encode ?: ""}", u, ExtractorLinkType.M3U8) {
                this.quality = if ((v.quality ?: 0) == 0) 1080 else v.quality ?: -1
                this.referer = "$mainUrl/"
            })
        }
        return videos.isNotEmpty()
    }

    // ---------------- Melolo ----------------

    private suspend fun fetchMeloloList(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val wrapper = tryParseJson<MeloloResponse>(text) ?: return emptyList()
        val books = wrapper.data?.extractBooks() ?: emptyList()
        return books.mapNotNull { it.toSearchResponse() }
    }

    private fun MeloloBook.toSearchResponse(): SearchResponse? {
        val id = bookId?.takeIf { it.isNotBlank() } ?: return null
        val t = bookName?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(t, "$mainUrl/melolo/$id", TvType.TvSeries) {
            this.posterUrl = thumbUrl
        }
    }

    private suspend fun loadMelolo(url: String, id: String): LoadResponse {
        val text = fetchJson("$API_BASE/melolo/detail?book_id=$id") ?: throw ErrorLoadingException("Gagal memuat detail Melolo")
        val wrapper = tryParseJson<MeloloDetail>(text) ?: throw ErrorLoadingException("Detail Melolo tidak valid")
        val vd = wrapper.data?.videoData ?: throw ErrorLoadingException("Detail Melolo kosong")
        val title = vd.seriesTitle?.takeIf { it.isNotBlank() } ?: "Drama"
        val poster = vd.seriesCover
        val episodes = (vd.videoList ?: emptyList()).mapNotNull { v ->
            val vid = v.vid?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode("$mainUrl/melolo/ep/$vid") {
                this.name = v.title ?: "Episode ${v.vidIndex ?: 0}"
                this.episode = v.vidIndex ?: 0
                this.posterUrl = v.cover ?: poster
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadMeloloLinks(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = fetchJson("$API_BASE/melolo/episode?videoId=$videoId") ?: return false
        val stream = tryParseJson<MeloloStream>(text) ?: return false
        val urls = buildList {
            stream.qualities?.forEach { q ->
                q.streamUrl?.takeIf { it.isNotBlank() }?.let { u -> add(streamQuality(u, q.definition)) }
            }
            stream.streamUrl?.takeIf { it.isNotBlank() }?.let { add(streamQuality(it, null)) }
        }.distinctBy { it.url }

        urls.forEach { link ->
            callback.invoke(link)
        }
        return urls.isNotEmpty()
    }

    private suspend fun streamQuality(url: String, definition: String?): ExtractorLink {
        val fixed = if (url.startsWith("http://api.sansekai.my.id")) {
            url.replace("http://api.sansekai.my.id", "https://api.sansekai.my.id")
        } else url
        return newExtractorLink("Melolo", definition ?: "Melolo", fixed, ExtractorLinkType.VIDEO) {
            this.quality = getQualityFromName(definition ?: "")
            this.referer = "$mainUrl/"
        }
    }

    // ---------------- FreeReels ----------------

    private suspend fun fetchFreeReelsList(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val data = tryParseJson<FreeReelsForYou>(text) ?: return emptyList()
        return data.data?.items?.filter { it.key?.isNotBlank() == true && it.title?.isNotBlank() == true }
            ?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun FreeReelsItem.toSearchResponse(): SearchResponse? {
        val k = key?.takeIf { it.isNotBlank() } ?: return null
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(t, "$mainUrl/freereels/$k", TvType.TvSeries) {
            this.posterUrl = cover
        }
    }

    private suspend fun loadFreeReels(url: String, id: String): LoadResponse {
        val text = fetchJson("$API_BASE/freereels/detailAndAllEpisode?key=$id") ?: throw ErrorLoadingException("Gagal memuat detail FreeReels")
        val detail = tryParseJson<FreeReelsDetail>(text) ?: throw ErrorLoadingException("Detail FreeReels tidak valid")
        val info = detail.data?.info ?: throw ErrorLoadingException("Detail FreeReels kosong")
        val title = info.name?.takeIf { it.isNotBlank() } ?: "Drama"
        val episodes = (info.episodeList ?: emptyList()).mapIndexedNotNull { index, ep ->
            newEpisode("$mainUrl/freereels/ep/$id/$index") {
                this.name = ep.name ?: "Episode ${index + 1}"
                this.episode = index + 1
                this.posterUrl = ep.cover ?: info.cover
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = info.cover
            this.plot = info.desc
        }
    }

    private suspend fun loadFreeReelsLinks(
        id: String,
        index: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = fetchJson("$API_BASE/freereels/detailAndAllEpisode?key=$id") ?: return false
        val detail = tryParseJson<FreeReelsDetail>(text) ?: return false
        val ep = detail.data?.info?.episodeList?.getOrNull(index) ?: return false

        val h264 = ep.externalAudioH264M3u8?.takeIf { it.isNotBlank() }
        val h265 = ep.externalAudioH265M3u8?.takeIf { it.isNotBlank() }
        var found = false

        if (h264 != null) {
            callback.invoke(newExtractorLink("FreeReels", "FreeReels H264", h264, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName("720")
                this.referer = "$mainUrl/"
            })
            found = true
        }
        if (h265 != null) {
            callback.invoke(newExtractorLink("FreeReels", "FreeReels H265", h265, ExtractorLinkType.M3U8) {
                this.quality = getQualityFromName("1080")
                this.referer = "$mainUrl/"
            })
            found = true
        }

        val indoSub = ep.subtitleList?.firstOrNull { it.language.equals("id-ID", ignoreCase = true) }
        val subUrl = indoSub?.vtt ?: indoSub?.subtitle
        subUrl?.takeIf { it.isNotBlank() }?.let { subtitleCallback(newSubtitleFile("Indonesia", it)) }

        return found
    }

    // ---------------- DramaNova ----------------

    private suspend fun fetchDramaNovaList(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val data = tryParseJson<DramaNovaPaged>(text) ?: return emptyList()
        return data.rows.mapNotNull { it.toSearchResponse() }
    }

    private suspend fun fetchDramaNovaDrama18(url: String): List<SearchResponse> {
        val text = fetchJson(url) ?: return emptyList()
        val data = tryParseJson<DramaNovaDrama18>(text) ?: return emptyList()
        val modules = data.data?.flatMap { it.recommendModules ?: emptyList() } ?: emptyList()
        val seen = mutableSetOf<String>()
        return modules.mapNotNull { m ->
            if (m.dramaId == null || !seen.add(m.dramaId)) return@mapNotNull null
            m.toSearchResponse()
        }
    }

    private fun DramaNovaItem.toSearchResponse(): SearchResponse? {
        val id = dramaId?.takeIf { it.isNotBlank() } ?: return null
        val t = title?.takeIf { it.isNotBlank() } ?: return null
        return newAnimeSearchResponse(t, "$mainUrl/dramanova/$id", TvType.TvSeries) {
            this.posterUrl = posterImgUrl?.takeIf { it.isNotBlank() } ?: posterImg
        }
    }

    private suspend fun loadDramaNova(url: String, id: String): LoadResponse {
        val text = fetchJson("$API_BASE/dramanova/detail?dramaId=$id") ?: throw ErrorLoadingException("Gagal memuat detail DramaNova")
        val wrapper = tryParseJson<DramaNovaDetailWrapper>(text) ?: throw ErrorLoadingException("Detail DramaNova tidak valid")
        val detail = wrapper.data ?: throw ErrorLoadingException("Detail DramaNova kosong")
        val title = detail.title?.takeIf { it.isNotBlank() } ?: "Drama"
        val poster = detail.posterImgUrl?.takeIf { it.isNotBlank() } ?: detail.posterImg
        val episodes = (detail.episodes ?: emptyList()).mapNotNull { ep ->
            val fileId = ep.fileId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newEpisode("$mainUrl/dramanova/ep/$fileId") {
                this.name = ep.episodeTitle ?: "Episode ${ep.episodeNumber ?: 0}"
                this.episode = ep.episodeNumber ?: 0
                this.posterUrl = poster
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = detail.synopsis ?: detail.description
            this.tags = detail.categories?.mapNotNull { it.name }
        }
    }

    private suspend fun loadDramaNovaLinks(
        fileId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val text = fetchJson("$API_BASE/dramanova/getvideo?fileId=$fileId") ?: return false
        val wrapper = tryParseJson<DramaNovaVideo>(text) ?: return false
        val result = wrapper.Result ?: return false

        var found = false
        result.PlayInfoList?.forEach { play ->
            val main = play.MainPlayUrl?.takeIf { it.isNotBlank() } ?: return@forEach
            callback.invoke(newExtractorLink("DramaNova", "DramaNova ${play.Definition ?: ""}", main, ExtractorLinkType.VIDEO) {
                this.quality = getQualityFromName(play.Definition ?: "")
                this.referer = "$mainUrl/"
            })
            found = true
            val backup = play.BackupPlayUrl?.takeIf { it.isNotBlank() }
            if (backup != null) {
                callback.invoke(newExtractorLink("DramaNova", "DramaNova ${play.Definition ?: ""} Backup", backup, ExtractorLinkType.VIDEO) {
                    this.quality = getQualityFromName(play.Definition ?: "")
                    this.referer = "$mainUrl/"
                })
            }
        }

        val indo = result.SubtitleInfoList?.firstOrNull {
            it.Language.equals("in", ignoreCase = true) || it.Language.equals("id", ignoreCase = true) ||
                it.Language?.startsWith("id-", ignoreCase = true) == true
        }
        indo?.SubtitleUrl?.takeIf { it.isNotBlank() }?.let { subtitleCallback(newSubtitleFile("Indonesia", it)) }

        return found
    }

    // ---------------- Dispatch ----------------

    override suspend fun load(url: String): LoadResponse {
        val path = url.removePrefix(mainUrl).trimStart('/').split('/').filter { it.isNotBlank() }
        val platform = path.getOrNull(0) ?: throw ErrorLoadingException("URL tidak valid")
        val id = path.getOrNull(1) ?: throw ErrorLoadingException("ID tidak valid")
        return when (platform) {
            "dramabox" -> loadDramaBox(url, id)
            "pinedrama" -> loadPineDrama(url, id)
            "reelshort" -> loadReelShort(url, id)
            "melolo" -> loadMelolo(url, id)
            "freereels" -> loadFreeReels(url, id)
            "dramanova" -> loadDramaNova(url, id)
            else -> throw ErrorLoadingException("Platform tidak dikenali")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val path = data.removePrefix(mainUrl).trimStart('/').split('/').filter { it.isNotBlank() }
        if (path.size < 3) return false
        return when (path[0]) {
            "dramabox" -> loadDramaBoxLinks(path[1], path[2].toIntOrNull() ?: return false, subtitleCallback, callback)
            "pinedrama" -> loadPineDramaLinks(path[1], path[2].toIntOrNull() ?: return false, callback)
            "reelshort" -> loadReelShortLinks(path[1], path[2].toIntOrNull() ?: return false, callback)
            "melolo" -> loadMeloloLinks(path[1], callback)
            "freereels" -> loadFreeReelsLinks(path[1], path[2].toIntOrNull() ?: return false, subtitleCallback, callback)
            "dramanova" -> loadDramaNovaLinks(path[1], subtitleCallback, callback)
            else -> false
        }
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val jobs = listOf(
            async { searchDramaBox(encoded) },
            async { searchPineDrama(encoded) },
            async { searchReelShort(encoded) },
            async { searchMelolo(encoded) },
            async { searchDramaNova(encoded) },
        )
        jobs.mapNotNull { it.await() }.flatten().distinctBy { it.url }
    }

    private suspend fun searchDramaBox(encoded: String): List<SearchResponse> = try {
        fetchDramaBoxList("$API_BASE/dramabox/search?query=$encoded")
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    private suspend fun searchPineDrama(encoded: String): List<SearchResponse> = try {
        val text = fetchJson("$API_BASE/pinedrama/search?query=$encoded") ?: return emptyList()
        val data = tryParseJson<PineDramaSearch>(text) ?: return emptyList()
        data.results.mapNotNull { it.toSearchResponse() }
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    private suspend fun searchReelShort(encoded: String): List<SearchResponse> = try {
        val text = fetchJson("$API_BASE/reelshort/search?query=$encoded") ?: return emptyList()
        val data = tryParseJson<ReelShortSearch>(text) ?: return emptyList()
        data.results.mapNotNull { r ->
            val id = r.bookId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val t = r.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newAnimeSearchResponse(t, "$mainUrl/reelshort/$id", TvType.TvSeries) {
                this.posterUrl = r.cover
            }
        }
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    private suspend fun searchMelolo(encoded: String): List<SearchResponse> = try {
        val text = fetchJson("$API_BASE/melolo/search?query=$encoded") ?: return emptyList()
        val wrapper = tryParseJson<MeloloSearch>(text) ?: return emptyList()
        val books = wrapper.data?.searchData?.flatMap { it.books ?: emptyList() } ?: emptyList()
        books.mapNotNull { it.toSearchResponse() }
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    private suspend fun searchFreeReels(encoded: String): List<SearchResponse> = try {
        val text = fetchJson("$API_BASE/freereels/search?query=$encoded") ?: return emptyList()
        val data = tryParseJson<FreeReelsSearch>(text) ?: return emptyList()
        data.data?.items?.filter { it.key?.isNotBlank() == true && it.title?.isNotBlank() == true }
            ?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    private suspend fun searchDramaNova(encoded: String): List<SearchResponse> = try {
        fetchDramaNovaList("$API_BASE/dramanova/search?query=$encoded&page=1")
    } catch (e: Exception) {
        logError(e)
        emptyList()
    }

    // ---------------- Models: DramaBox ----------------

    data class DramaBoxItem(
        val bookId: String? = null,
        val bookName: String? = null,
        val cover: String? = null,
        val coverWap: String? = null,
        val chapterCount: Int? = null,
        val introduction: String? = null,
        val tags: List<String>? = null,
    )

    data class DramaBoxDetail(
        val bookId: String? = null,
        val bookName: String? = null,
        val coverWap: String? = null,
        val chapterCount: Int? = null,
        val introduction: String? = null,
        val tags: List<String>? = null,
    )

    data class DramaBoxEpisode(
        val chapterId: String? = null,
        val chapterIndex: Int? = null,
        val chapterName: String? = null,
        val chapterImg: String? = null,
        val useMultiSubtitle: Int? = null,
        val cdnList: List<CdnInfo>? = null,
        val subLanguageVoList: List<SubLanguageVo>? = null,
    )

    data class CdnInfo(
        val cdnDomain: String? = null,
        val isDefault: Int? = null,
        val videoPathList: List<VideoPath>? = null,
    )

    data class VideoPath(
        val quality: Int? = null,
        val videoPath: String? = null,
        val isDefault: Int? = null,
        val isVipEquity: Int? = null,
    )

    data class SubLanguageVo(
        val captionLanguage: String? = null,
        val url: String? = null,
        val isDefault: Int? = null,
    )

    // ---------------- Models: PineDrama ----------------

    data class PineDramaResponse(
        val has_more: Boolean? = null,
        val cursor: String? = null,
        val collections: List<PineDramaCollection> = emptyList(),
    )

    data class PineDramaCollection(
        @JsonProperty("collection_id") val collectionId: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val total_episodes: Int? = null,
        val description: String? = null,
        val tags: List<String>? = null,
    )

    data class PineDramaSearch(
        val results: List<PineDramaCollection> = emptyList(),
    )

    data class PineDramaDetail(
        @JsonProperty("collection_id") val collectionId: String? = null,
        val title: String? = null,
        val description: String? = null,
        val total_episodes: Int? = null,
        @JsonProperty("cover_urls") val coverUrls: List<String>? = null,
    )

    data class PineDramaEpisode(
        @JsonProperty("episode_num") val episodeNum: Int? = null,
        val title: String? = null,
        @JsonProperty("best_url") val bestUrl: String? = null,
        val quality: String? = null,
        val main: PineDramaMain? = null,
    )

    data class PineDramaMain(
        @JsonProperty("indo_cdn_urls") val indoCdnUrls: List<String>? = null,
        @JsonProperty("indo_hd_cdn_urls") val indoHdCdnUrls: List<String>? = null,
        @JsonProperty("cdn_urls") val cdnUrls: List<String>? = null,
    )

    // ---------------- Models: ReelShort ----------------

    data class ReelShortResponse(
        val success: Boolean? = null,
        val data: ReelShortData? = null,
    )

    data class ReelShortData(
        val lists: List<ReelShortBook> = emptyList(),
    )

    data class ReelShortBook(
        @JsonProperty("book_id") val bookId: String? = null,
        @JsonProperty("book_title") val bookTitle: String? = null,
        @JsonProperty("book_pic") val bookPic: String? = null,
        @JsonProperty("special_desc") val specialDesc: String? = null,
    )

    data class ReelShortDetail(
        val success: Boolean? = null,
        val bookId: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val description: String? = null,
        val chapters: List<ReelShortChapter>? = null,
    )

    data class ReelShortChapter(
        val index: Int? = null,
        val chapterId: String? = null,
        val title: String? = null,
        val isLocked: Boolean? = null,
    )

    data class ReelShortEpisode(
        val success: Boolean? = null,
        val isLocked: Boolean? = null,
        val videoList: List<ReelShortVideo>? = null,
    )

    data class ReelShortVideo(
        val url: String? = null,
        val encode: String? = null,
        val quality: Int? = null,
    )

    data class ReelShortSearch(
        val success: Boolean? = null,
        val results: List<ReelShortSearchResult> = emptyList(),
    )

    data class ReelShortSearchResult(
        val bookId: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val description: String? = null,
    )

    // ---------------- Models: Melolo ----------------

    data class MeloloResponse(
        val code: Int? = null,
        val data: MeloloData? = null,
    )

    data class MeloloData(
        val cells: List<MeloloCell>? = null,
        val cell: MeloloCell? = null,
        val books: List<MeloloBook>? = null,
        val has_more: Boolean? = null,
        @JsonProperty("next_offset") val nextOffset: Int? = null,
    ) {
        fun extractBooks(): List<MeloloBook> {
            val result = mutableListOf<MeloloBook>()
            cells?.forEach { cell ->
                cell.cellData?.forEach { section ->
                    section.books?.let { result.addAll(it) }
                }
            }
            cell?.cellData?.forEach { section ->
                section.books?.let { result.addAll(it) }
            }
            books?.let { result.addAll(it) }
            val seen = mutableSetOf<String>()
            return result.filter { b ->
                if (b.bookId == null || !seen.add(b.bookId)) false else true
            }
        }
    }

    data class MeloloCell(
        @JsonProperty("cell_data") val cellData: List<MeloloSection>? = null,
    )

    data class MeloloSection(
        val books: List<MeloloBook>? = null,
    )

    data class MeloloBook(
        @JsonProperty("book_id") val bookId: String? = null,
        @JsonProperty("book_name") val bookName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        val abstract: String? = null,
    )

    data class MeloloSearch(
        val code: Int? = null,
        val data: MeloloSearchData? = null,
    )

    data class MeloloSearchData(
        @JsonProperty("search_data") val searchData: List<MeloloSearchSection>? = null,
    )

    data class MeloloSearchSection(
        val books: List<MeloloBook>? = null,
    )

    data class MeloloDetail(
        val code: Int? = null,
        val data: MeloloDetailData? = null,
    )

    data class MeloloDetailData(
        @JsonProperty("video_data") val videoData: MeloloVideoData? = null,
    )

    data class MeloloVideoData(
        @JsonProperty("series_title") val seriesTitle: String? = null,
        @JsonProperty("series_cover") val seriesCover: String? = null,
        @JsonProperty("episode_cnt") val episodeCnt: Int? = null,
        @JsonProperty("video_list") val videoList: List<MeloloVideo>? = null,
    )

    data class MeloloVideo(
        val vid: String? = null,
        @JsonProperty("vid_index") val vidIndex: Int? = null,
        val title: String? = null,
        val cover: String? = null,
    )

    data class MeloloStream(
        val success: Boolean? = null,
        val streamUrl: String? = null,
        val qualities: List<MeloloQuality>? = null,
    )

    data class MeloloQuality(
        val definition: String? = null,
        val streamUrl: String? = null,
    )

    // ---------------- Models: FreeReels ----------------

    data class FreeReelsForYou(
        val code: Int? = null,
        val data: FreeReelsForYouData? = null,
    )

    data class FreeReelsForYouData(
        val items: List<FreeReelsItem>? = null,
        @JsonProperty("page_info") val pageInfo: FreeReelsPageInfo? = null,
    )

    data class FreeReelsPageInfo(
        val has_more: Boolean? = null,
    )

    data class FreeReelsItem(
        val key: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val desc: String? = null,
    )

    data class FreeReelsSearch(
        val code: Int? = null,
        val data: FreeReelsSearchData? = null,
    )

    data class FreeReelsSearchData(
        val items: List<FreeReelsItem>? = null,
    )

    data class FreeReelsDetail(
        val code: Int? = null,
        val data: FreeReelsDetailData? = null,
    )

    data class FreeReelsDetailData(
        val info: FreeReelsInfo? = null,
    )

    data class FreeReelsInfo(
        val id: String? = null,
        val name: String? = null,
        val desc: String? = null,
        val cover: String? = null,
        @JsonProperty("episode_list") val episodeList: List<FreeReelsEpisode>? = null,
    )

    data class FreeReelsEpisode(
        val id: String? = null,
        val name: String? = null,
        val cover: String? = null,
        @JsonProperty("external_audio_h264_m3u8") val externalAudioH264M3u8: String? = null,
        @JsonProperty("external_audio_h265_m3u8") val externalAudioH265M3u8: String? = null,
        @JsonProperty("subtitle_list") val subtitleList: List<FreeReelsSubtitle>? = null,
    )

    data class FreeReelsSubtitle(
        val language: String? = null,
        val subtitle: String? = null,
        val vtt: String? = null,
    )

    // ---------------- Models: DramaNova ----------------

    data class DramaNovaPaged(
        val code: Int? = null,
        val rows: List<DramaNovaItem> = emptyList(),
    )

    data class DramaNovaDrama18(
        val code: Int? = null,
        val data: List<DramaNovaCategory>? = null,
    )

    data class DramaNovaCategory(
        @JsonProperty("categoryKey") val categoryKey: String? = null,
        @JsonProperty("recommendModules") val recommendModules: List<DramaNovaItem>? = null,
    )

    data class DramaNovaItem(
        val dramaId: String? = null,
        val title: String? = null,
        @JsonProperty("posterImgUrl") val posterImgUrl: String? = null,
        @JsonProperty("posterImg") val posterImg: String? = null,
        val synopsis: String? = null,
        val description: String? = null,
    )

    data class DramaNovaDetailWrapper(
        val code: Int? = null,
        val data: DramaNovaDetail? = null,
    )

    data class DramaNovaDetail(
        val dramaId: String? = null,
        val title: String? = null,
        @JsonProperty("posterImgUrl") val posterImgUrl: String? = null,
        @JsonProperty("posterImg") val posterImg: String? = null,
        val synopsis: String? = null,
        val description: String? = null,
        val categories: List<DramaNovaCategoryDetail>? = null,
        val episodes: List<DramaNovaEpisode>? = null,
    )

    data class DramaNovaCategoryDetail(
        val name: String? = null,
    )

    data class DramaNovaEpisode(
        val id: String? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("episodeTitle") val episodeTitle: String? = null,
        @JsonProperty("fileId") val fileId: String? = null,
    )

    data class DramaNovaVideo(
        val Result: DramaNovaVideoResult? = null,
    )

    data class DramaNovaVideoResult(
        val Vid: String? = null,
        @JsonProperty("PlayInfoList") val PlayInfoList: List<DramaNovaPlayInfo>? = null,
        @JsonProperty("SubtitleInfoList") val SubtitleInfoList: List<DramaNovaSubtitleInfo>? = null,
    )

    data class DramaNovaPlayInfo(
        val Definition: String? = null,
        @JsonProperty("MainPlayUrl") val MainPlayUrl: String? = null,
        @JsonProperty("BackupPlayUrl") val BackupPlayUrl: String? = null,
    )

    data class DramaNovaSubtitleInfo(
        val Language: String? = null,
        @JsonProperty("SubtitleUrl") val SubtitleUrl: String? = null,
    )
}