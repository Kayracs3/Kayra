version = 1

cloudstream {
    description = "SetFilmIzle film ve dizi sağlayıcısı"
    authors = listOf("Kayras3")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.setfilmizle.ltd/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
