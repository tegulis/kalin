import com.ncorti.ktfmt.gradle.KtfmtExtension
import com.ncorti.ktfmt.gradle.TrailingCommaManagementStrategy
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.gradle.ktfmt) apply false
    alias(libs.plugins.gradle.shadow) apply false
    alias(libs.plugins.gradle.versions) apply false
    alias(libs.plugins.kotest) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.jte) apply false
    alias(libs.plugins.dokka)
}

allprojects {
    group = "net.tegulis.kalin"
    version = "0.2.0"
}

/** Maven artifact ids of the shipped modules. */
val publishedModules =
    mapOf(
        "lib" to "kalin",
        "json" to "kalin-json",
        "testtools" to "kalin-testtools",
        "rendering-jte" to "kalin-rendering-jte",
        "inject" to "kalin-inject",
        "bundle" to "kalin-bundle",
    )

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(25)
            compilerOptions {
                languageVersion.set(KotlinVersion.KOTLIN_2_4)
                apiVersion.set(KotlinVersion.KOTLIN_2_4)
                jvmTarget.set(JvmTarget.JVM_25)
                allWarningsAsErrors.set(true)
                freeCompilerArgs.addAll(
                    "-Xreturn-value-checker=check",
                    "-Xjdk-release=25",
                    "-opt-in=net.tegulis.kalin.KalinInternalApi",
                )
            }
            if (project.name in publishedModules) explicitApi()
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            enableAssertions = true
            maxHeapSize = "2g"
            jvmArgs("--enable-native-access=ALL-UNNAMED", "-XX:+EnableDynamicAgentLoading")
            testLogging {
                events = setOf(TestLogEvent.FAILED, TestLogEvent.SKIPPED)
                exceptionFormat = TestExceptionFormat.FULL
                showExceptions = true
                showCauses = true
                showStackTraces = true
            }
            reports.all { required = false }
            addTestListener(
                object : TestListener {
                    override fun beforeSuite(suite: TestDescriptor) {}

                    override fun beforeTest(testDescriptor: TestDescriptor) {}

                    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}

                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        if (suite.parent == null) {
                            println(
                                "${project.name}: ${result.testCount} tests, ${result.successfulTestCount} passed, " +
                                    "${result.failedTestCount} failed, ${result.skippedTestCount} skipped"
                            )
                        }
                    }
                }
            )
            // Engine/transport matrix: -Pkalin.engine=EVENT_LOOP -Pkalin.transport=EPOLL
            listOf("kalin.engine", "kalin.transport", "kalin.testMode").forEach { key ->
                project.findProperty(key)?.let { systemProperty(key, it.toString()) }
            }
        }
    }

    plugins.withId("com.ncorti.ktfmt.gradle") {
        extensions.configure<KtfmtExtension> {
            kotlinLangStyle()
            maxWidth.set(120)
            removeUnusedImports.set(true)
            trailingCommaManagementStrategy.set(TrailingCommaManagementStrategy.COMPLETE)
        }
        // Format code before compiling; check formatting only when explicitly asked for
        tasks.withType<KotlinCompile>().configureEach { dependsOn("ktfmtFormat") }
        afterEvaluate {
            tasks.matching { it.name.startsWith("ktfmtCheck") }.configureEach {
                enabled = gradle.startParameter.taskNames.any { requested -> requested.endsWith(name) }
            }
        }
    }

    publishedModules[name]?.let { artifact ->
        apply(plugin = "java-library")
        apply(plugin = "maven-publish")
        // Sources come from the Kotlin plugin's kotlinSourcesJar; the javadoc jar holds the Dokka HTML documentation
        apply(plugin = "org.jetbrains.dokka")
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            moduleName.set(artifact)
            dokkaGeneratorIsolation.set(
                ProcessIsolation {
                    maxHeapSize.set("2g")
                    jvmArgs.add("-XX:ActiveProcessorCount=4")
                }
            )
        }
        val dokkaJar =
            tasks.register<Jar>("dokkaJar") {
                group = "documentation"
                description = "Packs the Dokka HTML documentation as the javadoc jar"
                archiveClassifier.set("javadoc")
                dependsOn("dokkaGeneratePublicationHtml")
                from(layout.buildDirectory.dir("dokka/html"))
            }
        // Apache License 2.0, section 4: ship the license and the NOTICE file with every artifact
        val licenseFiles = rootProject.files("LICENSE", "NOTICE")
        tasks.withType<Jar>().configureEach {
            archiveBaseName.set(artifact)
            from(licenseFiles) { into("META-INF") }
            manifest {
                attributes(
                    "Implementation-Title" to artifact,
                    "Implementation-Version" to project.version,
                    "Automatic-Module-Name" to "net.tegulis.kalin" + if (artifact == "kalin") "" else "." + artifact.removePrefix("kalin-").replace('-', '.'),
                )
            }
        }
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("maven") {
                    artifactId = artifact
                    from(components["java"])
                    afterEvaluate {
                        // The Kotlin plugin's sources jar is an org.gradle.jvm.tasks.Jar, which withType<Jar> misses
                        val sourcesJar =
                            tasks.named<org.gradle.jvm.tasks.Jar>("kotlinSourcesJar") {
                                from(licenseFiles) { into("META-INF") }
                            }
                        artifact(sourcesJar) { classifier = "sources" }
                    }
                    artifact(dokkaJar)
                    pom {
                        name.set(artifact)
                        description.set(
                            "Kalin: a tiny 100% Kotlin web framework with a Javalin-compatible API and its own HTTP/1.1 server"
                        )
                        url.set("https://github.com/tegulis/kalin")
                        licenses {
                            license {
                                name.set("The Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers { developer { id.set("tegulis"); name.set("Tegulis") } }
                        scm {
                            url.set("https://github.com/tegulis/kalin")
                            connection.set("scm:git:https://github.com/tegulis/kalin.git")
                        }
                    }
                }
            }
            repositories {
                // Local staging repository; real publishing is configured at release time
                maven {
                    name = "staging"
                    url = uri(rootProject.layout.buildDirectory.dir("staging-repo"))
                }
            }
        }
    }
}

/** Allowed runtime dependencies per published module, from dependency-allowlist.txt. */
val dependencyAllowList: Map<String, List<String>> =
    file("dependency-allowlist.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.groupBy(
        { it.substringBefore(':').trim() },
        { it.substringAfter(':').trim() },
    )

subprojects {
    if (name !in publishedModules) return@subprojects
    val rules = dependencyAllowList[name].orEmpty()
    val checkDependencies =
        tasks.register("checkDependencies") {
            group = "verification"
            description = "Verifies the runtime dependencies against dependency-allowlist.txt (docs/DESIGN.md §5.4)"
            val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
            val moduleName = project.name
            doLast {
                val violations = ArrayList<String>()
                val seen = HashSet<String>()
                fun visit(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    for (dep in component.dependencies) {
                        if (dep !is org.gradle.api.artifacts.result.ResolvedDependencyResult) continue
                        val selected = dep.selected
                        val id = selected.moduleVersion ?: continue
                        val key = "${id.group}:${id.name}"
                        if (!seen.add(key)) continue
                        val internal = selected.id is org.gradle.api.artifacts.component.ProjectComponentIdentifier
                        val ok = internal || rules.any { r -> r == key || (r.endsWith(":*") && r.removeSuffix(":*") == id.group) }
                        if (!ok) violations.add(key)
                        visit(selected)
                    }
                }
                visit(root.get())
                if (violations.isNotEmpty()) {
                    throw GradleException("Unapproved runtime dependencies of $moduleName: ${violations.joinToString()}")
                }
            }
        }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkDependencies) }
}

repositories { mavenCentral() }

dokka { moduleName.set("Kalin") }

// Aggregated API documentation of all published modules: ./gradlew :dokkaGenerate (build/dokka/html)
dependencies { publishedModules.keys.forEach { dokka(project(":$it")) } }
