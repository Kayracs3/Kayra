// CloudStream updates require a higher version number for existing installations.
version = 27

cloudstream {
    description = "DiziDAD — tüm diziler, filmler ve eksiksiz sezon/bölüm listeleri"
    authors = listOf("Kayracs3")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "tr"

    iconUrl = "https://www.google.com/s2/favicons?domain=dizidad.com&sz=%size%"
}
