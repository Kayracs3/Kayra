version = 18

android {
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

cloudstream {
    authors     = listOf("hexated", "kayracs3")
    language    = "tr"
    description = "Türkiye'nin en hızlı hd film izleme sitesi"
    status      = 1
    tvTypes     = listOf("Movie", "TvSeries")
    iconUrl     = "https://www.google.com/s2/favicons?domain=hdfilmcehennemi.com&sz=%size%"
}
