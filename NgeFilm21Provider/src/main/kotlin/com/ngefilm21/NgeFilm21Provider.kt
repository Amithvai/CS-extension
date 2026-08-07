package com.ngefilm21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private data class RpmResponse(
    val source: String? = null,
    val hlsVideoTiktok: String? = null,
)

private fun String.cleanSlashes(): String = replace("\\/", "/")

class Ngefilm21Provider : MainAPI() {
    override var mainUrl = "https://new37.ngefilm.site"
    override var name = "NgeFilm21"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    companion object {
        private const val UA_BROWSER = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
        private const val RPM_KEY = "6b69656d7469656e6d75613931316361"
        private const val RPM_IV = "313233343536373839306f6975797472"
        private const val RPM_PLAYER_DOMAIN = "playerngefilm21.rpmlive.online"

        private val REGEX_RESIZE = Regex("""-\d+x\d+""")
        private val REGEX_RPM_ID = Regex("""rpmlive\.online.*?[#&?]id=([a-zA-Z0-9]+)|rpmlive\.online.*?#([a-zA-Z0-9]+)""")
        private val REGEX_VIBUXER = Regex("""(?i)(?:src|href)\s*=\s*["'](https://(?:hglink\.(?:to|com|net)|vibuxer\.(?:com|net|to)|masukestin\.(?:com|net)|gradehgplus\.com|hgplaycdn\.com)/e/[a-zA-Z0-9]+)["']""")
        private val REGEX_KRAKEN = Regex("""src=["'](https://krakenfiles\.com/embed-video/[^"']+)["']""")
        private val REGEX_EMBED_HOSTS = Regex("""(?i)src=["'](https://[^"']*(?:short\.icu|mixdrop|xshotcok|hxfile)[^"']*)["']""")
        private val REGEX_EMBED_ALL = Regex("""(?:src|data-litespeed-src)=["'](https?://(?!(?:www\.)?googletagmanager|www\.google|data:)[^"']+)["']""")
        private val REGEX_EVAL_PACKED = Regex("""eval\(function\(p,a,c,k,e,d.*?\.split\('\|'\)\)""")
        private val REGEX_M3U8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
        private val REGEX_M3U8_REL = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
        private val REGEX_HASH = Regex("""hash\s*:\s*["']([^"']+)["']""")
        private val REGEX_KRAKEN_SOURCE = Regex("""<source[^>]+src=["'](https:[^"']+)["']""")
        private val REGEX_KRAKEN_VIDEO = Regex("""src=["'](https:[^"']+/play/video/[^"']+)["']""")
        private val REGEX_EP_NUMBER = Regex("""(\d+)""")
        private val REGEX_NON_HEX = Regex("[^0-9a-fA-F]")
    }

    private fun Element.getImageAttr(): String? {
        var url = this.attr("data-src").ifEmpty { this.attr("src") }
        if (url.isEmpty()) {
            val srcset = this.attr("srcset")
            if (srcset.isNotEmpty()) {
                url = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull() ?: ""
            }
        }
        return if (url.isNotEmpty()) {
            httpsify(url).replace(REGEX_RESIZE, "")
        } else null
    }

    private val categories = listOf(
        Pair("Latest Update", ""),
        Pair("Top Rating", "?s=&search=advanced&post_type=&index=&orderby=rating&genre=&movieyear=&country=&quality="),
        Pair("Indonesia Category", "/country/indonesia"),
        Pair("Western Category", "/country/usa"),
        Pair("Malaysia Category", "/country/malaysia"),
        Pair("Korean Category", "/country/korea"),
        Pair("Philippines Category", "/country/philippines"),
        Pair("Japan Category", "/country/japan"),
        Pair("Vietnam Category", "/country/viet-nam"),
        Pair("Chinese Category", "/country/china"),
        Pair("Canada Category", "/country/canada"),
        Pair("France Category", "/country/france"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homeItems = coroutineScope {
            categories.map { (title, urlPath) ->
                async {
                    val finalUrl = if (urlPath.isEmpty()) {
                        "$mainUrl/page/$page/"
                    } else if (urlPath.contains("?")) {
                        val split = urlPath.split("?")
                        "$mainUrl/page/$page/?${split[1]}"
                    } else {
                        "$mainUrl$urlPath/page/$page/"
                    }

                    try {
                        val document = app.get(finalUrl, timeout = 15_000L).document
                        val items = document.select("article.item-infinite").mapNotNull { it.toSearchResult() }
                        if (items.isNotEmpty()) HomePageList(title, items) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return newHomePageResponse(homeItems, hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".entry-title a")?.text() ?: return null
        val href = this.selectFirst(".entry-title a")?.attr("href") ?: return null
        val qualityText = this.selectFirst(".gmr-quality-item")?.text()?.trim() ?: "HD"
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = selectFirst(".content-thumbnail img")?.getImageAttr()
            addQuality(qualityText)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        return app.get("$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv", timeout = 15_000L).document
            .select("article.item-infinite").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, timeout = 15_000L).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".gmr-movie-data figure img")?.getImageAttr()
        val plotText = document.selectFirst("div.entry-content[itemprop='description'] p")?.text()?.trim()
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val yearText = document.selectFirst(".gmr-moviedata a[href*='year']")?.text()?.toIntOrNull()
        val ratingText = document.selectFirst("[itemprop='ratingValue']")?.text()?.trim()
        val tagsList = document.select(".gmr-moviedata a[href*='genre']").map { it.text() }
        val actorsList = document.select("[itemprop='actors'] a").map { it.text() }
        val trailerUrl = document.selectFirst("a.gmr-trailer-popup")?.attr("href")

        val epElements = document.select(".gmr-listseries a").filter { it.attr("href").contains("/eps/") }
        val isSeries = epElements.isNotEmpty()
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        if (isSeries) {
            val episodes = epElements.mapNotNull {
                val href = it.attr("href")
                if (href.isBlank()) return@mapNotNull null
                newEpisode(href) {
                    this.name = it.attr("title").removePrefix("Permalink ke ")
                    this.episode = REGEX_EP_NUMBER.find(it.text())?.groupValues?.get(1)?.toIntOrNull()
                }
            }
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                applyCommonMetadata(poster, plotText, yearText, ratingText, tagsList, actorsList, trailerUrl)
            }
        } else {
            return newMovieLoadResponse(title, url, type, url) {
                applyCommonMetadata(poster, plotText, yearText, ratingText, tagsList, actorsList, trailerUrl)
            }
        }
    }

    private fun LoadResponse.applyCommonMetadata(
        poster: String?,
        plotText: String?,
        yearText: Int?,
        ratingText: String?,
        tagsList: List<String>,
        actorsList: List<String>,
        trailerUrl: String?
    ) {
        this.posterUrl = poster
        this.plot = plotText
        this.year = yearText
        this.score = Score.from10(ratingText)
        this.tags = tagsList
        this.actors = actorsList.map { ActorData(Actor(it)) }
        if (!trailerUrl.isNullOrEmpty()) this.trailers.add(TrailerData(trailerUrl, null, false))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data, timeout = 15_000L).document
            val playerLinks = document.select(".muvipro-player-tabs a").mapNotNull { it.attr("href") }.toMutableList()
            if (playerLinks.isEmpty()) playerLinks.add(data)

            coroutineScope {
                playerLinks.distinct().map { playerUrl ->
                    async {
                        try {
                            val fixedUrl = if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
                            val pageContent = app.get(fixedUrl, headers = mapOf("User-Agent" to UA_BROWSER), timeout = 15_000L).text

                            // RPM — handle first
                            REGEX_RPM_ID.find(pageContent)?.let { match ->
                                val id = match.groupValues[1].ifEmpty { match.groupValues[2] }
                                if (id.isNotEmpty()) extractRpm(id, callback)
                            }

                            // Collect all embed URLs from page content
                            val embedUrls = mutableSetOf<String>()
                            REGEX_EMBED_ALL.findAll(pageContent).forEach { embedUrls.add(it.groupValues[1]) }
                            REGEX_VIBUXER.findAll(pageContent).forEach { embedUrls.add(it.groupValues[1]) }
                            REGEX_KRAKEN.findAll(pageContent).forEach { embedUrls.add(it.groupValues[1]) }
                            REGEX_EMBED_HOSTS.findAll(pageContent).forEach { embedUrls.add(it.groupValues[1]) }

                            embedUrls.forEach { url ->
                                when {
                                    url.contains("xshotcok") || url.contains("hxfile") -> extractXshotcok(url, callback)
                                    url.contains("krakenfiles") -> extractKrakenManual(url, callback)
                                    url.contains("masukestin") || url.contains("hglink") || url.contains("vibuxer") ||
                                    url.contains("gradehgplus") || url.contains("hgplaycdn") ->
                                        extractMasukestin(url, fixedUrl, callback)
                                    url.contains("short.icu") -> {
                                        val finalUrl = app.get(url, headers = mapOf("Referer" to fixedUrl), timeout = 15_000L).url
                                        loadExtractor(finalUrl, subtitleCallback, callback)
                                    }
                                    url.contains("morencius") -> extractPackedM3u8(url, "Morencius", callback)
                                    url.contains("vidara.to") -> extractPackedM3u8(url, "Vidara", callback)
                                    url.contains("abyss") -> extractWithFallback(url, "Abyss", subtitleCallback, callback)
                                    url.contains("hgcloud") -> loadExtractor(url, subtitleCallback, callback)
                                    url.contains("bangjago") -> extractWithFallback(url, "Bangjago", subtitleCallback, callback)
                                    else -> loadExtractor(url, subtitleCallback, callback)
                                }
                            }
                        } catch (_: Exception) {
                            // Player URL failed — try remaining servers
                        }
                    }
                }.awaitAll()
            }
            true
        } catch (e: Exception) {
            throw ErrorLoadingException(e.message ?: "Gagal memuat video")
        }
    }

    private suspend fun extractXshotcok(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl
            ), timeout = 15_000L).text

            val packedCode = REGEX_EVAL_PACKED.find(response)?.value ?: return
            val unpackedJs = Unpacker.unpack(packedCode)

            REGEX_M3U8.find(unpackedJs)?.groupValues?.get(1)?.let { rawLink ->
                val cleanLink = rawLink.cleanSlashes()
                val origin = try {
                    java.net.URL(url).protocol + "://" + java.net.URL(url).host
                } catch (_: Exception) { "https://xshotcok.com" }

                callback.invoke(
                    newExtractorLink(
                        "Xshotcok",
                        "Server 5 (Xshotcok)",
                        cleanLink,
                        ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to url,
                            "Origin" to origin
                        )
                    }
                )
            }
        } catch (_: Exception) {
            // Xshotcok extractor gagal — lewati
        }
    }

    private suspend fun extractMasukestin(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        try {
            val domain = "masukestin.com"
            val urlHost = try { java.net.URL(url).host } catch (_: Exception) { "hglink.to" }
            val response = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to referer,
                "Origin" to "https://$urlHost",
                "Upgrade-Insecure-Requests" to "1"
            ), timeout = 15_000L)

            val doc = response.text
            val cookies = response.cookies
            val videoId = url.substringAfter("/e/").substringBefore("?").substringBefore("\"").substringBefore("'")
            val packedCode = REGEX_EVAL_PACKED.find(doc)?.value

            if (packedCode != null) {
                val unpackedJs = Unpacker.unpack(packedCode)
                var linkM3u8 = REGEX_M3U8_REL.find(unpackedJs)?.groupValues?.get(1)

                if (linkM3u8 == null) {
                    val hash = REGEX_HASH.find(unpackedJs)?.groupValues?.get(1)
                    if (hash != null) {
                        val apiUrl = "https://$domain/dl?op=view&file_code=$videoId&hash=$hash&embed=1&referer=$urlHost"
                        val apiRes = app.get(apiUrl, headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to url,
                            "X-Requested-With" to "XMLHttpRequest"
                        ), cookies = cookies, timeout = 15_000L).text

                        linkM3u8 = REGEX_M3U8_REL.find(apiRes)?.groupValues?.get(1)
                    }
                }

                if (linkM3u8 != null) {
                    val cleanUrl = linkM3u8.cleanSlashes()
                    val finalUrl = if (cleanUrl.startsWith("/")) "https://$domain$cleanUrl" else cleanUrl
                    callback.invoke(
                        newExtractorLink(
                            "Masukestin",
                            "Masukestin (Server 3)",
                            finalUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf(
                                "User-Agent" to UA_BROWSER,
                                "Referer" to "https://$domain/",
                                "Origin" to "https://$domain"
                            )
                        }
                    )
                }
            } else {
                val directM3u8 = REGEX_M3U8_REL.find(doc)?.groupValues?.get(1)
                if (directM3u8 != null) {
                    val cleanUrl = directM3u8.cleanSlashes()
                    val finalUrl = if (cleanUrl.startsWith("/")) "https://$domain$cleanUrl" else cleanUrl

                    callback.invoke(
                        newExtractorLink(
                            "Masukestin",
                            "Masukestin (Direct)",
                            finalUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to "https://$domain/")
                        }
                    )
                }
            }
        } catch (_: Exception) {
            // Masukestin extractor gagal — lewati
        }
    }

    object Unpacker {
        fun unpack(packedJS: String): String {
            try {
                val startIdx = packedJS.indexOf("}('")
                if (startIdx == -1) return packedJS
                val argsString = packedJS.substring(startIdx + 3)
                val splitIdx = argsString.lastIndexOf("'.split('|')")
                if (splitIdx == -1) return packedJS
                val coreData = argsString.substring(0, splitIdx)
                val parts = coreData.split(",")
                if (parts.size < 4) return packedJS
                val dictRaw = parts.last().trim('\'', '"')
                val dictionary = dictRaw.split("|")
                val count = parts[parts.size - 2].toIntOrNull() ?: return packedJS
                val radix = parts[parts.size - 3].toIntOrNull() ?: return packedJS
                val payloadRaw = coreData.substring(0, coreData.lastIndexOf(",$radix"))
                val payload = payloadRaw.trim('\'', '"')
                var decoded = payload
                for (i in count - 1 downTo 0) {
                    val token = encodeBase(i, radix)
                    val word = if (i < dictionary.size && dictionary[i].isNotEmpty()) dictionary[i] else token
                    decoded = decoded.replace(token, word)
                }
                return decoded.replace("\\", "")
            } catch (e: Exception) { return packedJS }
        }

        private fun encodeBase(n: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            var num = n
            if (num == 0) return "0"
            val sb = StringBuilder()
            while (num > 0) {
                sb.append(chars[num % radix])
                num /= radix
            }
            return sb.reverse().toString()
        }
    }

    private suspend fun extractRpm(id: String, callback: (ExtractorLink) -> Unit) {
        try {
            val headers = mapOf(
                "Host" to RPM_PLAYER_DOMAIN,
                "User-Agent" to UA_BROWSER,
                "Referer" to "https://$RPM_PLAYER_DOMAIN/",
                "Origin" to "https://$RPM_PLAYER_DOMAIN",
                "X-Requested-With" to "XMLHttpRequest"
            )
            val domain = mainUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            val videoApi = "https://$RPM_PLAYER_DOMAIN/api/v1/video?id=$id&w=1920&h=1080&r=$domain"
            val encryptedRes = app.get(videoApi, headers = headers, timeout = 15_000L).text
            val jsonStr = if (encryptedRes.isBlank()) return
            else if (encryptedRes.trim().startsWith("{")) encryptedRes else decryptAES(encryptedRes)
            if (jsonStr.isBlank()) return

            tryParseJson<RpmResponse>(jsonStr)?.let { data ->
                data.source?.let { link ->
                    callback.invoke(newExtractorLink("RPM Live", "RPM Live", link.cleanSlashes(), ExtractorLinkType.M3U8) {
                        this.referer = "https://$RPM_PLAYER_DOMAIN/"
                    })
                }
                data.hlsVideoTiktok?.let { link ->
                    callback.invoke(newExtractorLink("RPM Live (Backup)", "RPM Live (Backup)", "https://$RPM_PLAYER_DOMAIN${link.cleanSlashes()}", ExtractorLinkType.M3U8) {
                        this.referer = "https://$RPM_PLAYER_DOMAIN/"
                    })
                }
            }
        } catch (e: Exception) {
            // RPM extractor failed — player might use a different server
        }
    }

    private suspend fun extractKrakenManual(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val text = app.get(url, headers = mapOf("User-Agent" to UA_BROWSER, "Referer" to mainUrl), timeout = 15_000L).text
            val videoUrl = REGEX_KRAKEN_SOURCE.find(text)?.groupValues?.get(1)
                ?: REGEX_KRAKEN_VIDEO.find(text)?.groupValues?.get(1)
            videoUrl?.let { clean ->
                callback.invoke(newExtractorLink("Krakenfiles", "Krakenfiles", clean.replace("&amp;", "&").replace("\\", ""), ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.headers = mapOf("User-Agent" to UA_BROWSER)
                })
            }
        } catch (_: Exception) {
            // Krakenfiles extractor gagal — lewati
        }
    }

    private suspend fun extractPackedM3u8(url: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl
            ), timeout = 15_000L).text

            val packedCode = REGEX_EVAL_PACKED.find(response)?.value ?: return
            val unpackedJs = Unpacker.unpack(packedCode)

            REGEX_M3U8.find(unpackedJs)?.groupValues?.get(1)?.let { rawLink ->
                val cleanLink = rawLink.cleanSlashes()
                val origin = try {
                    java.net.URL(url).protocol + "://" + java.net.URL(url).host
                } catch (_: Exception) { "https://${sourceName.lowercase()}.com" }

                callback.invoke(
                    newExtractorLink(sourceName, "$sourceName", cleanLink, ExtractorLinkType.M3U8) {
                        this.headers = mapOf(
                            "User-Agent" to UA_BROWSER,
                            "Referer" to url,
                            "Origin" to origin
                        )
                    }
                )
            }
        } catch (_: Exception) { }
    }

    private suspend fun extractWithFallback(url: String, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to UA_BROWSER,
                "Referer" to mainUrl
            ), timeout = 15_000L)
            REGEX_M3U8.find(resp.text)?.groupValues?.get(1)?.let { link ->
                callback.invoke(newExtractorLink(name, name, link.cleanSlashes(), ExtractorLinkType.M3U8) {
                    this.referer = resp.url
                    this.headers = mapOf("User-Agent" to UA_BROWSER)
                })
            }
        } catch (_: Exception) { }
        loadExtractor(url, subtitleCallback, callback)
    }

    private fun decryptAES(text: String): String {
        if (text.isEmpty()) return ""
        return try {
            val cleanHex = text.replace(REGEX_NON_HEX, "")
            if (cleanHex.length % 2 != 0) return ""
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexToBytes(RPM_KEY), "AES"), IvParameterSpec(hexToBytes(RPM_IV)))
            String(cipher.doFinal(hexToBytes(cleanHex)))
        } catch (e: Exception) { "" }
    }

    private fun hexToBytes(s: String): ByteArray {
        val len = s.length
        if (len % 2 != 0) return ByteArray(0)
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            if (hi == -1 || lo == -1) return ByteArray(0)
            data[i / 2] = ((hi shl 4) + lo).toByte()
        }
        return data
    }
}
