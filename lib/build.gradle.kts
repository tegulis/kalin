import com.ncorti.ktfmt.gradle.TrailingCommaManagementStrategy
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
}

kotlin.jvmToolchain(25)

dependencies {
    // LOGGING
    implementation(libs.kotlin.logging)
    implementation(libs.slf4j)
    // TESTING
    // Kotest
    testImplementation(libs.kotest.runner)
}

kotest { enablePowerAssert = true }

tasks.withType<Test> {
    useJUnitPlatform()
    // Check formatting before testing for good measure, even though it will be skipped since it is disabled below
    dependsOn("ktfmtCheck")
    enableAssertions = true
    // Extra settings for very verbose testing
    testLogging {
        events = TestLogEvent.entries.filter { it != TestLogEvent.STARTED }.toSet()
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
    // Google Truth: don't clean stack traces
    systemProperty("com.google.common.truth.disable_stack_trace_cleaning", "true")
    // Don't generate reports
    reports.all { required = false }
    // Temporary solution for MockK: https://github.com/mockk/mockk/issues/1171
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

ktfmt {
    kotlinLangStyle()
    maxWidth.set(120)
    removeUnusedImports.set(true)
    trailingCommaManagementStrategy.set(TrailingCommaManagementStrategy.COMPLETE)
}

// Don't check formatting unless explicitly asked for
listOf("", "Main", "Scripts", "Test").forEach { taskName ->
    tasks.named("ktfmtCheck$taskName") { enabled = gradle.startParameter.taskNames.contains(this.name) }
}

// Format code before compiling
tasks.withType<KotlinCompile> { dependsOn("ktfmtFormat") }

val generatedSourcesDir = "${layout.buildDirectory.get()}/generated/kotlin"

tasks.register<Copy>("generateKotlin") {
    description = "Generates Kotlin sources from templates"
    val templateContext = mapOf("version" to project.version)
    inputs.properties(templateContext)
    from("src/template/kotlin")
    into(generatedSourcesDir)
    expand(templateContext)
}

tasks.named("compileKotlin").configure { dependsOn("generateKotlin") }

sourceSets.main { kotlin.srcDir(generatedSourcesDir) }
