version = 2

cloudstream {
    description = "Dizigecesi film ve dizi sağlayıcısı"
    authors = listOf("Kayracs3")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://dizigecesi.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
