allprojects {
    group = "net.tegulis.template"
    version = "5.0.0"
}

// Kotlin plugin asks to be added here to prevent breaking the build
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
