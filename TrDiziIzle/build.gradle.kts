version = 24
dependencies {
    // Coroutines are provided by CloudStream at runtime; compile against the API only.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}

cloudstream {
    description = "TRDiziIzle — dizi ve film sağlayıcısı"
    authors = listOf("Kayracs3")
    status = 1
    tvTypes = listOf("TvSeries", "Movie")
    language = "tr"
    iconUrl = "https://www.google.com/s2/favicons?domain=trdiziizle.tv&sz=%size%"
}
