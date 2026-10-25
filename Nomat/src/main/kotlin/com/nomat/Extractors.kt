package com.nomat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Auditori 2026-10-25:
 *  - filelions.to  : hidup, masih jwplayer packed → VidHidePro (terverifikasi menghasilkan m3u8).
 *  - filemoon.sx   : berubah total menjadi SPA "Byse" (React) — packed script hilang.
 *                    Butuh extractor khusus FileMoonByse (captcha POW + AES-256-GCM).
 *  - streamwish.to / streamhide.to : muncul sporadis di server list, .to sering 522/timeout.
 *  - dingtezuni.com, earnvids.com, bingezove.com, ryderjet.com, playhydrax.com :
 *                    sudah tidak pernah muncul di server list nontonhemat → registrasi dibuang.
 */

class FilelionsTo : VidHidePro() {
    override var name = "Filelions"
    override var mainUrl = "https://filelions.to"
}

class StreamWishTo : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class StreamHideTo : Filesim() {
    override var name = "StreamHide"
    override var mainUrl = "https://streamhide.to"
}

/* ============================== FileMoon (Byse SPA) ============================== */
/**
 * filemoon.sx kini berupa aplikasi "Byse" (React) dengan proteksi:
 *   1. POST /api/videos/{code}/embed/captcha          → pow_token, pow_nonce, pow_difficulty
 *   2. Solve POW custom: cari counter s sehingga hashByse("{nonce}:{s}")
 *      punya leading-zero-bits >= difficulty, lalu
 *      POST /api/videos/{code}/embed/captcha/verify   → {status:"ok", token}
 *   3. POST /api/videos/{code}/embed/playback         → payload terenkripsi AES-256-GCM
 *      (header X-Captcha-Token + X-Embed-* wajib)
 *   4. key = b64url(key_parts[version-1]) + b64url(key_parts[size-1-version])
 *   5. Dekrip payload → { sources: [{url, label, mime_type, ...}] }
 *
 * Algoritme hash & skema key diporting dari bundle SPA (pow-*.js + videoPagesBundle).
 * Sudah divalidasi end-to-end 2026-10-25 (m3u8 1080p keluar).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
private data class ByseCaptcha(
    @JsonProperty("pow_nonce") val powNonce: String? = null,
    @JsonProperty("pow_difficulty") val powDifficulty: Int = 0,
    @JsonProperty("pow_token") val powToken: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ByseVerify(
    val status: String? = null,
    val token: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BysePlayback(
    val version: Int = 0,
    val iv: String? = null,
    val payload: String? = null,
    @JsonProperty("key_parts") val keyParts: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BysePlaybackRoot(
    val playback: BysePlayback? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ByseDetails(
    @JsonProperty("embed_frame_url") val embedFrameUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ByseSource(
    val label: String? = null,
    @JsonProperty("mime_type") val mimeType: String? = null,
    val url: String? = null,
    val height: Int? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class ByseDecrypt(
    val sources: List<ByseSource>? = null,
)

class FileMoonByse : ExtractorApi() {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val requiresReferer = true

    companion object {
        // Konstanta hash Byse (dari pow chunk SPA).
        private const val STATE_SIZE = 512
        private const val STATE_MASK = STATE_SIZE - 1 // 511
        private const val MIX_ROUNDS = 2
        private const val GOLDEN = 0x9E3779B1.toInt() // 2654435761
        private const val SILVER = 0x85EBCA77.toInt() // 2246822519
        private const val MAX_POW_TRIES = 120_000_000L
        private const val POW_BUDGET_MS = 20_000L

        /** rotl 32-bit (padanan re() di JS: (t<<e|t>>>(32-e))>>>0). */
        private fun rotl(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

        /** Hash custom Byse — port persis dari function gr() di pow-*.js.
         * wordCount=1 cukup untuk solver: word-0 nol berarti >=32 leading zeros. */
        private fun byseHashWords(input: ByteArray, wordCount: Int = 8): IntArray {
            val e = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt())

            fun mix() { // function ye()
                e[0] += e[1]
                e[3] = rotl(e[3] xor e[0], 16)
                e[2] += e[3]
                e[1] = rotl(e[1] xor e[2], 12)
                e[0] += e[1]
                e[3] = rotl(e[3] xor e[0], 8)
                e[2] += e[3]
                e[1] = rotl(e[1] xor e[2], 7)
            }

            for (b in input) {
                e[0] += b.toInt() and 0xFF
                e[0] = rotl(e[0], 7)
                mix()
            }
            repeat(8) { mix() }

            val r = IntArray(STATE_SIZE)
            for (i in 0 until STATE_SIZE) {
                mix()
                r[i] = e[0] xor e[2]
            }
            repeat(MIX_ROUNDS) {
                for (s in 0 until STATE_SIZE) {
                    val a = r[s] and STATE_MASK
                    var c = r[s] + r[a]
                    c = rotl(c, 13)
                    c = c xor (r[(s + 1) and STATE_MASK] * GOLDEN)
                    r[s] = c
                    e[0] = e[0] xor c
                    mix()
                }
            }

            val words = IntArray(wordCount)
            val group = STATE_SIZE / 8
            for (i in 0 until wordCount) {
                mix()
                var s = e[0]
                for (j in 0 until group) {
                    val d = r[i * group + j]
                    s += d
                    s = rotl(s, 5)
                    s = s xor (d * SILVER)
                }
                words[i] = s xor e[2]
            }
            return words
        }

        /** Jumlah leading zero bits dari 8 kata (padanan wr() di JS). */
        private fun leadingZeroBits(words: IntArray): Int {
            var total = 0
            for (w in words) {
                if (w == 0) {
                    total += 32
                    continue
                }
                return total + Integer.numberOfLeadingZeros(w)
            }
            return total
        }

        /**
         * Solver POW: counter terkecil s sehingga
         * leadingZeroBits(hash("{nonce}:{s}")) >= difficulty. Return null jika habis budget.
         */
        private fun solvePow(nonce: String, difficulty: Int): String? {
            if (difficulty <= 0) return "0"
            val prefix = (nonce + ":").toByteArray(Charsets.ISO_8859_1)
            val start = System.currentTimeMillis()
            var s = 0L
            while (s < MAX_POW_TRIES) {
                // Optimasi: cukup hitung kata pertama — untuk difficulty <= 32, solved
                // iff word-0 punya leading zeros >= difficulty.
                val input = prefix + s.toString().toByteArray(Charsets.ISO_8859_1)
                val words = byseHashWords(input, 1)
                if (leadingZeroBits(words) >= difficulty) return s.toString()
                s++
                if (s % 4096 == 0L && System.currentTimeMillis() - start > POW_BUDGET_MS) return null
            }
            return null
        }

        private fun b64UrlDecode(value: String): ByteArray =
            Base64.getUrlDecoder().decode(value.trim())
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = url.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return

        val parentOrigin = runCatching {
            val u = URI(referer ?: "$mainUrl/")
            "${u.scheme}://${u.host}"
        }.getOrNull() ?: mainUrl

        // 1. Minta tantangan POW.
        val captcha = runCatching {
            app.post(
                "$mainUrl/api/videos/$code/embed/captcha",
                json = mapOf("fingerprint" to emptyMap<String, String>()),
                headers = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.5",
                    "X-Embed-Origin" to parentOrigin,
                ),
                referer = referer,
                timeout = 15_000L,
            ).text.let { tryParseJson<ByseCaptcha>(it) }
        }.getOrNull()
        if (captcha?.powToken == null || captcha.powNonce == null) {
            logError(Exception("$name: captcha challenge gagal untuk $code"))
            return
        }

        // 2. Solve + verifikasi.
        val solution = solvePow(captcha.powNonce, captcha.powDifficulty)
        if (solution == null) {
            logError(Exception("$name: POW difficulty ${captcha.powDifficulty} tidak terjawab"))
            return
        }
        val verified = runCatching {
            app.post(
                "$mainUrl/api/videos/$code/embed/captcha/verify",
                json = mapOf("pow_token" to captcha.powToken, "solution" to solution),
                headers = mapOf("Accept" to "*/*"),
                referer = referer,
                timeout = 15_000L,
            ).text.let { tryParseJson<ByseVerify>(it) }
        }.getOrNull()
        val captchaToken = verified?.token
        if (verified?.status != "ok" || captchaToken.isNullOrBlank()) {
            logError(Exception("$name: verifikasi POW ditolak"))
            return
        }

        // 3. Playback terenkripsi. Origin embed bisa berpindah → fallback ke embed_frame_url.
        val embedHeaders = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "X-Captcha-Token" to captchaToken,
            "X-Embed-Origin" to parentOrigin,
            "X-Embed-Referer" to "$parentOrigin/",
            "X-Embed-Parent" to "$parentOrigin/",
        )
        var root = fetchPlayback(code, mainUrl, embedHeaders, referer)
        if (root?.playback == null) {
            val frame = runCatching {
                app.get(
                    "$mainUrl/api/videos/$code/embed/details",
                    headers = mapOf("Accept" to "application/json"),
                    referer = referer,
                    timeout = 15_000L,
                ).text.let { tryParseJson<ByseDetails>(it) }
            }.getOrNull()?.embedFrameUrl
            val frameOrigin = frame?.let { runCatching { URI(it).let { u -> "${u.scheme}://${u.host}" } }.getOrNull() }
            if (!frameOrigin.isNullOrBlank() && frameOrigin != mainUrl) {
                root = fetchPlayback(code, frameOrigin, embedHeaders, referer)
            }
        }
        val playback = root?.playback
        if (playback?.iv == null || playback.payload == null) {
            logError(Exception("$name: playback kosong untuk $code"))
            return
        }

        // 4. Susun key AES dari key_parts sesuai version.
        val parts = playback.keyParts
        if (parts.isNullOrEmpty() || parts.size < 2) return
        val v = playback.version
        if (v < 1 || v > parts.size) return
        val first = runCatching { b64UrlDecode(parts[v - 1]) }.getOrNull() ?: return
        val second = runCatching { b64UrlDecode(parts[parts.size - 1 - v]) }.getOrNull() ?: return
        val iv = runCatching { b64UrlDecode(playback.iv!!) }.getOrNull() ?: return
        if (iv.size != 12) return

        val key = first + second
        if (key.size != 16 && key.size != 24 && key.size != 32) {
            logError(Exception("$name: panjang key AES tidak valid (${key.size})"))
            return
        }

        // 5. Dekrip AES-256-GCM.
        val plain = runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, iv)
            )
            cipher.doFinal(b64UrlDecode(playback.payload!!))
        }.getOrNull()
        if (plain == null) {
            logError(Exception("$name: dekripsi GCM gagal untuk $code"))
            return
        }
        val data = tryParseJson<ByseDecrypt>(String(plain, Charsets.UTF_8))
        val sources = data?.sources.orEmpty().filter { !it.url.isNullOrBlank() }
        if (sources.isEmpty()) {
            logError(Exception("$name: sources kosong untuk $code"))
            return
        }

        val headers = mapOf("Referer" to "$mainUrl/")
        for (src in sources) {
            val linkUrl = src.url!!
            val quality = src.height ?: src.label?.filter { it.isDigit() }?.toIntOrNull() ?: Qualities.Unknown.value
            if (linkUrl.contains(".m3u8", true) || src.mimeType?.contains("mpegurl", true) == true) {
                runCatching {
                    M3u8Helper.generateM3u8(name, linkUrl, "$mainUrl/", headers = headers)
                        .forEach(callback)
                }.onFailure { logError(it) }
            } else {
                callback(
                    newExtractorLink(
                        name,
                        name,
                        url = linkUrl,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
            }
        }
    }

    private suspend fun fetchPlayback(
        code: String,
        origin: String,
        headers: Map<String, String>,
        referer: String?
    ): BysePlaybackRoot? = runCatching {
        app.post(
            "$origin/api/videos/$code/embed/playback",
            json = mapOf("fingerprint" to emptyMap<String, String>()),
            headers = headers,
            referer = referer,
            timeout = 15_000L,
        ).text.let { tryParseJson<BysePlaybackRoot>(it) }
    }.onFailure { logError(it) }.getOrNull()
}
