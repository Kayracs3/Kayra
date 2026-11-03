version = 12

dependencies {
    // Coroutines are provided by CloudStream at runtime; compile against the API only.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}

cloudstream {
    description = "BeyazElma canlı TV sağlayıcısı"
    authors = listOf("Kayracs3")
    status = 1
    tvTypes = listOf("Live")
    language = "tr"
    iconUrl = "https://tv.newtane.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
