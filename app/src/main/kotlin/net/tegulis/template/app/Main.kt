package net.tegulis.template.app

import io.github.oshai.kotlinlogging.KotlinLogging
import net.tegulis.template.Library
import net.tegulis.template.VERSION

private val logger = KotlinLogging.logger {}

fun main() {
    logger.atInfo {
        message = "Starting app version $VERSION"
        payload = mapOf("version" to VERSION)
    }
    println("Main version: $VERSION")
    println(Library.generateMessage("world"))
}
