pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudstreamPlugins"

val disabled = setOf(
    "__Temel",
    // Adult-content provider is not part of the build.
    "PatronSH"
)

rootDir
    .listFiles()
    ?.filter { it.isDirectory }
    ?.filter { !disabled.contains(it.name) }
    ?.filter {
        File(
            it,
            "build.gradle.kts"
        ).exists()
    }
    ?.forEach {
        include(":${it.name}")
    }
