import java.util.Properties

version = 487

dependencies {
    // CloudStream already provides coroutines at runtime. Compile against the
    // API here without bundling a second coroutine runtime into the plugin.
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
android {
    namespace = "com.Kayracs3"
    defaultConfig {
        val properties = Properties()
        // local.properties is developer-local and is intentionally not committed.
        // Keep CI builds reproducible when it is absent; allow environment values
        // to override local settings for hosted builds.
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.isFile) {
            localPropertiesFile.inputStream().use { properties.load(it) }
        }

        fun buildConfigString(name: String): String {
            val value = System.getenv(name) ?: properties.getProperty(name, "")
            val escaped = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
            return "\"$escaped\""
        }

        android.buildFeatures.buildConfig = true
        buildConfigField("String", "SIMKL_API", buildConfigString("SIMKL_API"))
        buildConfigField("String", "TMDB_KEY", buildConfigString("TMDB_KEY"))
        buildConfigField("String", "CASTLE_KEY", buildConfigString("CASTLE_KEY"))
        buildConfigField("String", "MOVIEBLAST_TOKEN", buildConfigString("MOVIEBLAST_TOKEN"))
        buildConfigField("String", "MOVIEBLAST_API", buildConfigString("MOVIEBLAST_API"))
        buildConfigField("String", "MOVIEBLAST_KEY", buildConfigString("MOVIEBLAST_KEY"))
    }
}

cloudstream {
    language = "en"
    description = "One stop solution for Movies, Series, Anime, AsianDrama and Torrents"
    authors = listOf("megix")
    status = 1
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "AsianDrama",
        "Anime",
        "Torrent"
    )

    iconUrl = "https://raw.githubusercontent.com/Kayracs3/Kayra/master/CineStream/icon.png"
}
