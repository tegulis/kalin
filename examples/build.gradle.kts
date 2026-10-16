plugins {
    alias(libs.plugins.gradle.ktfmt)
    alias(libs.plugins.kotest)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.jte)
}

description = "Kalin examples"

dependencies {
    implementation(project(":lib"))
    implementation(project(":json"))
    implementation(project(":rendering-jte"))
    implementation(project(":inject"))
    ksp(libs.kotlin.inject.compiler)
    testImplementation(project(":testtools"))
    testImplementation(libs.kotest.runner)
}

// Precompile the jte templates in src/main/jte into Java sources compiled with the module
jte {
    generate()
    binaryStaticContent = true
}

ksp { arg("me.tatarka.inject.dumpGraph", "true") }

// Run an example: ./gradlew :examples:run -Pexample=HelloWorld
tasks.register<JavaExec>("run") {
    group = "application"
    description =
        "Runs an example (-Pexample=HelloWorld|RestUsers|StaticFiles|SseChat|JteTemplates|Injected|ContextParameters)"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "net.tegulis.kalin.examples.${project.findProperty("example") ?: "HelloWorld"}Kt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
