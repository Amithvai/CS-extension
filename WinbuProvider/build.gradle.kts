// use an integer for version numbers
version = 1


cloudstream {
    language = "id"
    description = "Winbu — Streaming Anime, Donghua, Film dan TV Series"

    authors = listOf("MWK")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Anime",
        "AsianDrama",
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://winbu.net&size=%size%"
}