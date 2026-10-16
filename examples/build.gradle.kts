plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.kotlin.jvm)
}

description = "Kalin examples"

dependencies { implementation(project(":lib")) }
