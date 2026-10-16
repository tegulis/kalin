pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention").version("1.0.0")
}

rootProject.name = "kalin"

// Shipped library modules
include("lib", "json", "testtools", "rendering-jte", "inject")

// Standalone static file server
include("app")

// Examples and benchmarks (not published)
include("examples", "benchmarks")
