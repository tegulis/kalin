plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.kotlin.jvm)
}

description = "Kalin benchmarks"

dependencies {
    implementation(project(":lib"))
}
