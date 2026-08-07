package com.mwk.shared.data

import com.fasterxml.jackson.annotation.JsonProperty

data class OkRuVideo(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
)
