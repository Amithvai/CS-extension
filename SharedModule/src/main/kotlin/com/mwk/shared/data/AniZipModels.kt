package com.mwk.shared.data

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class MetaImage(
    @JsonProperty("coverType") val coverType: String?,
    @JsonProperty("url") val url: String?
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class MetaEpisode(
    @JsonProperty("episode") val episode: String?,
    @JsonProperty("airDateUtc") val airDateUtc: String?,
    @JsonProperty("airdate") val airdate: String? = null,
    @JsonProperty("runtime") val runtime: Int?,
    @JsonProperty("length") val length: Int? = null,
    @JsonProperty("image") val image: String?,
    @JsonProperty("title") val title: Map<String, String>?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("rating") val rating: String?,
    @JsonProperty("finaleType") val finaleType: String?
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class MetaAnimeData(
    @JsonProperty("titles") val titles: Map<String, String>? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("images") val images: List<MetaImage>? = null,
    @JsonProperty("episodes") val episodes: Map<String, MetaEpisode>? = null,
    @JsonProperty("mappings") val mappings: MetaMappings? = null
)

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class MetaMappings(
    @JsonProperty("themoviedb_id") val themoviedbId: Any? = null,
    @JsonProperty("thetvdb_id") val thetvdbId: Int? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("mal_id") val malId: Int? = null,
    @JsonProperty("anilist_id") val anilistId: Int? = null,
    @JsonProperty("kitsu_id") val kitsuId: String? = null
)

data class AniZipEpisodes(
    @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
)

data class AniZipEpisode(
    @JsonProperty("anidbEid") val anidbEid: String? = null
)
