package com.mwk.shared.data

import com.fasterxml.jackson.annotation.JsonProperty

data class KisskhMedia(
    @JsonProperty("episodesCount") val episodesCount: Int?,
    @JsonProperty("thumbnail") val thumbnail: String?,
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
)

data class KisskhDetail(
    @JsonProperty("description") val description: String?,
    @JsonProperty("releaseDate") val releaseDate: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("country") val country: String?,
    @JsonProperty("episodes") val episodes: ArrayList<KisskhEpisode>? = arrayListOf(),
    @JsonProperty("thumbnail") val thumbnail: String?,
    @JsonProperty("id") val id: Int?,
    @JsonProperty("title") val title: String?,
)

data class KisskhEpisode(
    @JsonProperty("id") val id: Int?,
    @JsonProperty("number") val number: Int?,
    @JsonProperty("sub") val sub: Int?,
)

data class KisskhKey(
    val id: String,
    val version: String,
    val key: String,
)

data class KisskhSources(
    @JsonProperty("Video") val video: String?,
    @JsonProperty("ThirdParty") val thirdParty: String?,
)

data class KisskhSubtitle(
    @JsonProperty("src") val src: String?,
    @JsonProperty("label") val label: String?,
)

data class KisskhResponses(
    @JsonProperty("data") val data: ArrayList<KisskhMedia>? = arrayListOf(),
)
