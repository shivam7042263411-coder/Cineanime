version = 1

cloudstream {
    language = "en"
    description = "Nuvio stream provider: Movies, Series, Anime with direct + torrent streams"
    authors = listOf("nuvio")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama",
        "Torrent"
    )

    iconUrl = "https://raw.githubusercontent.com/SaurabhKaperwan/CSX/refs/heads/master/CineStream/icon.png"
}
