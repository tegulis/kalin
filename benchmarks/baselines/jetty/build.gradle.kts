plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(25) }
application { mainClass.set("JettyMainKt") }
dependencies {
    implementation("org.eclipse.jetty:jetty-server:12.1.12")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
}
