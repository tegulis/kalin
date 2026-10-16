plugins {
    id("application")
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

description = "Kalin benchmarks (TechEmpower-style plaintext and JSON)"

application {
    mainClass = "net.tegulis.kalin.benchmarks.TfbKt"
    applicationDefaultJvmArgs =
        listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UseParallelGC", "-Xms512m", "-Xmx512m")
}

dependencies {
    implementation(project(":lib"))
    implementation(project(":json"))
}
