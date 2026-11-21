version = 10

dependencies {
    // Coroutines are provided by CloudStream at runtime; compile against the API only.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.1")
}

cloudstream {
    description = "DaddyLive 24/7 canlı TV kanalları (thedaddy.li)"
    authors = listOf("Kayracs3")
    status = 3
    tvTypes = listOf("Live")
    language = "en"
}

android {
    buildFeatures {
        buildConfig = true
    }
}
