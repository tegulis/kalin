plugins { alias(libs.plugins.kotlin.jvm) }

description = "Kalin bundle: kalin core and kalin-json"

dependencies {
    api(project(":lib"))
    api(project(":json"))
}
