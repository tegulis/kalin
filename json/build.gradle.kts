plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Kalin JSON support with kotlinx.serialization"

dependencies {
    api(project(":lib"))
    api(libs.kotlinx.serialization.json)
    testImplementation(project(":testtools"))
    testImplementation(libs.kotest.runner)
}
