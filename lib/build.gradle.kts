plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
}

description = "Kalin core: engines, transports, router, Context, SSE, static files, plugins"

dependencies {
    // kalin-core depends only on the Kotlin standard library and the JDK.
    testImplementation(project(":testtools"))
    testImplementation(project(":json"))
    testImplementation(libs.kotest.runner)
}

kotest { enablePowerAssert = true }

val generatedSourcesDir = layout.buildDirectory.dir("generated/kotlin")

val generateKotlin by
    tasks.registering(Copy::class) {
        description = "Generates Kotlin sources from templates"
        val templateContext = mapOf("version" to project.version)
        inputs.properties(templateContext)
        from("src/template/kotlin")
        into(generatedSourcesDir)
        expand(templateContext)
    }

tasks.named("compileKotlin").configure { dependsOn(generateKotlin) }

tasks.matching { it.name == "sourcesJar" || it.name.startsWith("ktfmt") }.configureEach { dependsOn(generateKotlin) }

sourceSets.main { kotlin.srcDir(generatedSourcesDir) }

tasks.jar { manifest { attributes("Enable-Native-Access" to "ALL-UNNAMED") } }
