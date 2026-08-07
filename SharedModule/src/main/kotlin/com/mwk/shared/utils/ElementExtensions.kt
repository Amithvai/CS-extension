package com.mwk.shared.utils

import org.jsoup.nodes.Element

fun Element.getImageAttr(): String? {
    return when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }
}

fun Element?.getIframeAttr(): String? {
    return this?.let {
        val lsSrc = it.attr("data-litespeed-src")
        if (lsSrc.isNotEmpty()) lsSrc else it.attr("src")
    }
}
