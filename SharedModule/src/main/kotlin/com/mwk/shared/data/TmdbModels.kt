package com.mwk.shared.data

import com.fasterxml.jackson.annotation.JsonProperty

data class TmdbImagesResponse(
    val logos: List<TmdbLogo>? = null
)

data class TmdbLogo(
    @param:JsonProperty("aspect_ratio") val aspectRatio: Double? = null,
    val height: Int? = null,
    @param:JsonProperty("iso_639_1") val iso6391: String? = null,
    @param:JsonProperty("file_path") val filePath: String? = null,
    @param:JsonProperty("vote_average") val voteAverage: Double? = null,
    @param:JsonProperty("vote_count") val voteCount: Int? = null,
    val width: Int? = null
)

data class TmdbDate(
    val today: String,
    val nextWeek: String,
    val lastWeekStart: String? = null,
    val monthStart: String? = null
)
