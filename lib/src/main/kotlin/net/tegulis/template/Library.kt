package net.tegulis.template

import io.github.oshai.kotlinlogging.KotlinLogging

object Library {
    // Fancy code to depend on kotlin-stdlib
    val versionText = VERSION.let { "Library version: $it" }

    private val logger = KotlinLogging.logger {}

    fun generateMessage(name: String): String {
        logger.atInfo {
            message = "Generating a message for $name"
            payload = mapOf("name" to name)
        }
        return "Hello $name!\n$versionText"
    }
}
