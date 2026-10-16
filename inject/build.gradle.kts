plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

description = "Kalin integration with kotlin-inject (compile-time dependency injection)"

dependencies {
    api(project(":lib"))
    api(libs.kotlin.inject.runtime)
    kspTest(libs.kotlin.inject.compiler)
    testImplementation(project(":testtools"))
    testImplementation(libs.kotest.runner)
}
