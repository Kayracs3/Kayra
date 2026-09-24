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
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudstreamPlugins"

val disabled = setOf(
    "__Temel"
)

rootDir
    .listFiles()
    ?.filter {
        it.isDirectory
    }
    ?.filter {
        !disabled.contains(it.name)
    }
    ?.filter {
        File(
            it,
            "build.gradle.kts"
        ).exists()
    }
    ?.forEach {
        include(":${it.name}")
    }
