version = 11

cloudstream {
    description = "Donghub — Streaming Donghua Subtitle Indonesia"
    language = "id"
    authors = listOf("Miku", "MWK")

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
        "AnimeMovie",
        "Cartoon",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://donghive.vip&size=%size%"
}
