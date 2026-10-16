plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(25) }
application { mainClass.set("JavalinMainKt") }
dependencies {
    implementation("io.javalin:javalin:7.2.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
