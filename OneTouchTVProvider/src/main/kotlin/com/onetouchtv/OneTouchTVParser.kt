package com.onetouchtv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

data class OneTouchTVParser(
    val day: List<TopMedia>? = emptyList(),
    val week: List<TopMedia>? = emptyList(),
    val month: List<TopMedia>? = emptyList()
) {
    data class TopMedia(
        @param:JsonProperty("_id") val id: String? = null,
        @param:JsonProperty("id") val id2: String? = null,
        val title: String? = null,
        val image: String? = null,
        val country: String? = null,
        val type: String? = null,
        val year: String? = null,
        val popularity: Int = 0,
        val status: String? = null,
        val releaseDate: String? = null,
        val isSub: Boolean = false
    )
}

// Unified type for RandomSlideShow and Recent (identical JSON shape)
data class MediaItem(
    @param:JsonProperty("_id") val id: String? = null,
    @param:JsonProperty("id") val id2: String? = null,
    val title: String? = null,
    val image: String? = null,
    val country: String? = null,
    val type: String? = null,
    val year: String? = null,
    val popularity: Long? = null,
    val description: String? = null,
    val status: String? = null,
    val releaseDate: String? = null,
    val isSub: Boolean? = null
)

data class MediaResult(
    val randomSlideShow: List<MediaItem>? = null,
    val recents: List<MediaItem>? = null,
    val result: ResultWrapper? = null
)

data class ResultWrapper(
    val randomSlideShow: List<MediaItem>? = null,
    val recents: List<MediaItem>? = null
)

data class SourceItem(
    val type: String? = null,
    val contentId: String? = null,
    val id: String? = null,
    val name: String? = null,
    val quality: String? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null
)

data class TrackItem(
    val file: String? = null,
    val name: String? = null,
    val isDefault: Boolean = false,
    val kind: String? = null,
    val format: String? = null
)

data class ParserResponse(
    val result: ParserResult? = null,
    val sources: List<SourceItem>? = null,
    val track: List<TrackItem>? = null,
    val tracks: List<TrackItem>? = null
)

data class ParserResult(
    val sources: List<SourceItem>? = null,
    val track: List<TrackItem>? = null,
    val tracks: List<TrackItem>? = null
)

fun parseSourcesAndTracks(decryptedJson: String): Pair<List<SourceItem>, List<TrackItem>> {
    val parsed = tryParseJson<ParserResponse>(decryptedJson) ?: return emptyList<SourceItem>() to emptyList<TrackItem>()
    val sources = parsed.result?.sources ?: parsed.sources ?: emptyList()
    val tracks = parsed.result?.track ?: parsed.result?.tracks ?: parsed.track ?: parsed.tracks ?: emptyList()
    return sources to tracks
}

data class Search(
    val status: Long = 0,
    val result: List<SearchResult> = emptyList()
)

data class SearchResult(
    val id: String? = null,
    val loklokContentId: String? = null,
    val isSub: Boolean = false,
    val title: String? = null,
    val image: String? = null,
    val type: String? = null,
    val year: String? = null,
    val source: String? = null,
    val status: String? = null,
    val loklokCategory: Long? = null,
    val episodes: List<Any?> = emptyList(),
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val otherTitles: List<String> = emptyList()
)

// Load/detail response models
data class LoadData(
    val title: String? = null,
    val image: String? = null,
    val poster: String? = null,
    val description: String? = null,
    val year: String? = null,
    val status: String? = null,
    val actors: List<ActorItem> = emptyList(),
    val genres: List<String> = emptyList(),
    val episodes: List<EpisodeItem> = emptyList()
)

data class ActorItem(
    val name: String? = null,
    val image: String? = null
)

data class EpisodeItem(
    val episode: String? = null,
    val identifier: String? = null,
    val playId: String? = null
)
