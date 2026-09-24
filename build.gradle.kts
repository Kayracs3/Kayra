import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")

        // -SNAPSHOT yerine sabit commit
        classpath(
            "com.github.recloudstream:gradle:32895aedb6366f5075cb99bbd2e6ce0a7cac325d"
        )

        classpath(
            "org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0"
        )
    }
}
