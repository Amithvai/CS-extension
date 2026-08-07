package com.mwk.shared.utils

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

private object UrlRegex {
    val IMAGE_SIZE_REGEX = Regex("[-.]?\\d{3,4}x\\d{3,4}")
    val SPACE_PLUS = "\\s+".toRegex()
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun String.fixUrlBloat(): String {
    return this.replace("\"", "").replace("\\", "")
}

fun String?.fixImageQuality(): String? {
    return this?.replace(UrlRegex.IMAGE_SIZE_REGEX, "")
}

fun String?.createSlug(): String? {
    return this?.filter { it.isWhitespace() || it.isLetterOrDigit() }
        ?.trim()
        ?.replace(UrlRegex.SPACE_PLUS, "-")
        ?.lowercase()
}

fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

fun encode(input: String): String = URLEncoder.encode(input, "utf-8").replace("+", "%20")

fun String.encodeUrl(): String {
    val url = java.net.URL(this)
    val uri = URI(url.protocol, url.userInfo, url.host, url.port, url.path, url.query, url.ref)
    return uri.toURL().toString()
}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }
    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

fun String.getHost(): String {
    return (URI(this).host ?: "").substringBeforeLast(".").substringAfterLast(".")
}
