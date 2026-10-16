plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
}

description = "Kalin FileRenderer adapter for jte templates"

dependencies {
    api(project(":lib"))
    api(libs.jte)
    // Needed only for hot reloading .kte templates in development
    compileOnly(libs.jte.kotlin)
    testImplementation(project(":testtools"))
    testImplementation(libs.jte.kotlin)
    // jte-kotlin compiles .kte templates with the embedded Kotlin compiler; it must be able to read the stdlib in use
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotest.runner)
}
