plugins {
    id("application")
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.shadow)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
}

description = "kalin-server: a standalone static file server built on Kalin"

application {
    mainClass = "${project.group}.${project.name}.MainKt"
    applicationName = "kalin-server"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks["shadowJar"].group = "distribution"

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    manifest { attributes("Enable-Native-Access" to "ALL-UNNAMED", "Implementation-Version" to project.version) }
    mergeServiceFiles()
}

listOf("startScripts", "distZip", "distTar", "shadowDistZip", "shadowDistTar").forEach { taskName ->
    tasks.named(taskName) {
        group = "disabled"
        enabled = false
    }
}

// Pack the shadowJar and asset files into a zip
tasks.register("packZip", Zip::class) {
    group = "distribution"
    dependsOn("shadowJar")
    archiveFileName = "kalin-server-${project.version}.zip"
    destinationDirectory = file("build/distributions")
    from("build/libs") { include("${project.name}-${project.version}-all.jar") }
    from("dist") { include("**/*") }
    from(rootProject.file("LICENSE"))
}

dependencies {
    implementation(project(":lib"))
    // LOGGING: Kalin logs through System.Logger, routed to SLF4J/Logback here
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j)
    runtimeOnly(libs.slf4j.jdk.platform.logging)
    runtimeOnly(libs.logback.classic)
    runtimeOnly(libs.logstash.logback.encoder)
    // TESTING
    testImplementation(project(":testtools"))
    testImplementation(libs.kotest.runner)
}

kotest { enablePowerAssert = true }
