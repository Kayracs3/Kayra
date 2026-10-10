import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
version = 2
cloudstream {
    description = "DiziSol film ve dizi sağlayıcısı; sezon, bölüm, metadata ve video kaynakları"
    authors = listOf("Kayracs3")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "tr"
    iconUrl = "https://dizisol.com/favicon.ico"
}

android {
    buildFeatures {
        buildConfig = true
    }
}


// Kotlin 2.4 metadata is newer than the D8 version bundled by the repo's AGP 8.7.3.
// Keep this module's emitted metadata readable by that D8 version.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xmetadata-version=2.1.0")
    }
}
