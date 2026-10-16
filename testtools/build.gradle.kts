plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Kalin test tools: testKalin { } and KalinTest.test(app) { server, client -> }"

dependencies {
    api(project(":lib"))
    testImplementation(project(":json"))
    testImplementation(libs.kotest.runner)
}
