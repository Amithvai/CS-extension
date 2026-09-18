package com.idlix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Ekstraktor untuk player yang memakai JS packer (eval(function(p,a,c,k,e,d){...})).
 *
 * Player yang ditangani (semuanya VidHide/StreamWish-style):
 *  - morencius.com/embed/xxx     (dipakai IDLIX teamhaupt.org)
 *  - vidara.to/e/xxx
 *  - xshotcok.com/e/xxx
 *  - hglink.to, vibuxer, masukestin, gradehgplus, hgplaycdn
 *
 * Cara kerja:
 *  1. Fetch halaman embed (dengan Referer provider)
 *  2. Cari blok eval(function(p,a,c,k,e,d){...})
 *  3. Unpack → cari URL .m3u8 di dalamnya
 *  4. Emit sebagai ExtractorLink M3U8 dengan header yang diperlukan
 */
object PackedPlayerExtractor {

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Host player yang ditangani ekstraktor ini */
    private val PACKED_HOSTS = listOf(
        "morencius", "vidara.to", "xshotcok", "hglink", "vibuxer",
        "masukestin", "gradehgplus", "hgplaycdn", "streamruby",
        "rubyvidhub", "svanila", "svilla", "vidhide", "listeamed",
        "bembed", "vgfplay", "vidguard", "turbovip", "turbovid"
    )

    private val REGEX_EVAL_PACKED = Regex(
        """eval\(function\(p,a,c,k,e,d.*?\.split\('\|'\)\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val REGEX_M3U8_ABS = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
    private val REGEX_M3U8_REL = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
    private val REGEX_HASH = Regex("""hash\s*:\s*["']([^"']+)["']""")

    /** Cek apakah URL ini ditangani oleh ekstraktor packed */
    fun handles(url: String): Boolean =
        PACKED_HOSTS.any { url.contains(it, ignoreCase = true) }

    /**
     * Coba ekstrak video dari player packed.
     * @return true jika berhasil memproduksi minimal 1 link
     */
    suspend fun extract(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourceName = hostName(url)
        return try {
            val response = app.get(
                url,
                headers = mapOf("User-Agent" to UA, "Referer" to (referer ?: url)),
                timeout = 15_000L
            ).text

            // 1) Coba unpack JS packed
            val packedCode = REGEX_EVAL_PACKED.find(response)?.value
            if (packedCode != null) {
                val unpackedJs = Unpacker.unpack(packedCode)
                if (unpackedJs != packedCode) {
                    val rawLink = REGEX_M3U8_ABS.find(unpackedJs)?.groupValues?.getOrNull(1)
                        ?: REGEX_M3U8_REL.find(unpackedJs)?.groupValues?.getOrNull(1)
                    if (!rawLink.isNullOrBlank()) {
                        emit(sourceName, rawLink.cleanSlashes(), url, callback)
                        return true
                    }
                }
            }

            // 2) Fallback: cari .m3u8 langsung di HTML mentah
            val direct = REGEX_M3U8_ABS.find(response)?.groupValues?.getOrNull(1)
            if (!direct.isNullOrBlank()) {
                emit(sourceName, direct.cleanSlashes(), url, callback)
                return true
            }

            // 3) Fallback: <source src="...">
            Regex("""<source[^>]+src=["'](https:[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(response)?.groupValues?.getOrNull(1)?.let {
                    emit(sourceName, it.cleanSlashes(), url, callback)
                    return true
                }

            // 4) Fallback: iframe di dalam player (chain ke player lain)
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(response)?.groupValues?.getOrNull(1)?.let { inner ->
                    if (inner.startsWith("http") && !inner.contains("about:blank")) {
                        // Serahkan ke loadExtractor generik
                        com.lagradost.cloudstream3.utils.loadExtractor(
                            inner, url, {}, callback
                        )
                        return true
                    }
                }

            false
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun emit(
        sourceName: String,
        link: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = try {
            val u = java.net.URI(pageUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            "https://${sourceName.lowercase()}.com"
        }
        callback.invoke(
            newExtractorLink(sourceName, sourceName, link, ExtractorLinkType.M3U8) {
                this.referer = pageUrl
                this.headers = mapOf(
                    "User-Agent" to UA,
                    "Referer" to pageUrl,
                    "Origin" to origin
                )
            }
        )
    }

    private fun hostName(url: String): String = runCatching {
        java.net.URI(url).host
            ?.removePrefix("www.")
            ?.substringBefore('.')
            ?.replaceFirstChar { it.uppercase() }
    }.getOrNull() ?: "Player"

    private fun String.cleanSlashes(): String = replace("\\/", "/")

    /** JS unpacker untuk packer Dean Edwards (p,a,c,k,e,d) */
    private object Unpacker {
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
                // PENTING: JS asli memakai \b (word boundary), bukan replace biasa.
                // Tanpa \b, token seperti "3" akan merusak angka di dalam URL.
                for (i in count - 1 downTo 0) {
                    val token = encodeBase(i, radix)
                    val word = if (i < dictionary.size && dictionary[i].isNotEmpty()) dictionary[i] else token
                    decoded = Regex("""\b${Regex.escape(token)}\b""").replace(decoded) { word }
                }
                return decoded.replace("\\", "")
            } catch (_: Exception) {
                return packedJS
            }
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
}
