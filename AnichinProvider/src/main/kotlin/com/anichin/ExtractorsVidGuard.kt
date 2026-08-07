package com.anichin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Base64

class Vidguardto1 : Vidguardto() {
    override val mainUrl = "https://bembed.net"
}

class Vidguardto2 : Vidguardto() {
    override val mainUrl = "https://listeamed.net"
}

class Vidguardto3 : Vidguardto() {
    override val mainUrl = "https://vgfplay.com"
}

open class Vidguardto : ExtractorApi() {
    companion object {
        private val SVG_PATTERN1 = """svg\s*[=:]\s*(\{(?:[^{}]|"[^"]*")*?\})""".toRegex(RegexOption.IGNORE_CASE)
        private val SVG_PATTERN2 = """svg\s*[=:]\s*(\{[^}]*?(?:stream|hash)[^}]*?\})""".toRegex(RegexOption.IGNORE_CASE)
        private val SVG_PATTERN3 = """\{[^}]*?"stream"[^}]*?"hash"[^}]*?\}""".toRegex()
        private val JSON_KEY_REGEX = Regex("""(\w+)\s*:""")
    }

    override val name = "Vidguard"
    override val mainUrl = "https://vidguard.to"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = app.get(getEmbedUrl(url), timeout = 15_000L)

        val evalScript = res.document.select("script:containsData(eval)").firstOrNull()?.data()
            ?: return

        val svgObject = extractSvgObject(evalScript) ?: return
        val watchlink = sigDecode(svgObject.stream)

        callback.invoke(
            newExtractorLink(this.name, name, watchlink) {
                this.referer = mainUrl
            }
        )
    }

    private fun extractSvgObject(scriptData: String): SvgObject? {
        val patterns = listOf(
            SVG_PATTERN1,
            SVG_PATTERN2,
            SVG_PATTERN3,
        )

        for (pattern in patterns) {
            for (match in pattern.findAll(scriptData)) {
                val raw = match.groupValues[1].ifBlank { match.value }
                val json = raw
                    .replace(JSON_KEY_REGEX) { "\"${it.groupValues[1]}\":" }
                    .replace("'", "\"")
                tryParseJson<SvgObject>(json)?.let { return it }
            }
        }

        return null
    }

    private fun sigDecode(url: String): String {
        val sigPart = url.substringAfter("sig=", "")
        if (sigPart.isBlank()) return url
        val sig = sigPart.split("&")[0]
        val t = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.getDecoder().decode((it + padding).toByteArray(Charsets.UTF_8)))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) {
                    if (i + 1 < size) {
                        this[i] = this[i + 1].also { this[i + 1] = this[i] }
                    }
                }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, t)
    }

    private fun getEmbedUrl(url: String): String {
        return url.takeIf { it.contains("/d/") || it.contains("/v/") }
            ?.replace("/d/", "/e/")?.replace("/v/", "/e/") ?: url
    }

    data class SvgObject(
        val stream: String,
        val hash: String
    )
}
