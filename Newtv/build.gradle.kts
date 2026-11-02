version = 1

cloudstream {
    description = "NewTV otomatik sağlayıcısı"
    authors = listOf("Kayracs3")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://tv.newtane.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
