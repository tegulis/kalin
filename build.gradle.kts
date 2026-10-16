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
}

allprojects {
    group = "net.tegulis.kalin"
    version = "0.1.0"
}

/** Maven artifact ids of the shipped modules. */
val publishedModules =
    mapOf(
        "lib" to "kalin",
        "json" to "kalin-json",
        "testtools" to "kalin-testtools",
        "rendering-jte" to "kalin-rendering-jte",
        "inject" to "kalin-inject",
    )

subprojects {
    repositories {
        mavenLocal()
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
        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }
        tasks.withType<Jar>().configureEach {
            archiveBaseName.set(artifact)
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
