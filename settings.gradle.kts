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
    // Keep the PatronSH provider out of the repository-wide build.
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
