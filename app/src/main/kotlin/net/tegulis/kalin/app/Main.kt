package net.tegulis.kalin.app

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.KalinBindException
import net.tegulis.kalin.VERSION

/**
 * `kalin-server`: serves the files of a directory over HTTP, like a minimal nginx or Apache, built on Kalin.
 *
 * Run `kalin-server --help` for the options.
 */
fun main(args: Array<String>) {
    val options =
        try {
            ServerOptions.parse(args)
        } catch (e: UsageException) {
            System.err.println("kalin-server: ${e.message}\n\n${ServerOptions.USAGE}")
            exitProcess(2)
        }
    if (options.showHelp) {
        println(ServerOptions.USAGE)
        return
    }
    if (options.showVersion) {
        println("kalin-server $VERSION")
        return
    }
    // Choose the logback configuration before the first logger is created
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    if (options.logFormat == LogFormat.JSON && System.getProperty("logback.configurationFile") == null) {
        System.setProperty("logback.configurationFile", "logback-json.xml")
    }
    val logger = KotlinLogging.logger("kalin-server")
    try {
        options.validate()
    } catch (e: UsageException) {
        System.err.println("kalin-server: ${e.message}")
        exitProcess(2)
    }
    val app = Kalin.create { config -> config.staticServer(options) }
    try {
        val start = System.nanoTime()
        app.start()
        val host = options.host ?: "0.0.0.0"
        logger.atInfo {
            message =
                "kalin-server $VERSION serving ${options.directory.toAbsolutePath().normalize()} on " +
                    "http://$host:${app.port()}${options.contextPath.trimEnd('/')}/ (${app.engineDescription}, " +
                    "started in ${(System.nanoTime() - start) / 1_000_000} ms)"
            payload =
                mapOf(
                    "port" to app.port(),
                    "directory" to options.directory.toString(),
                    "engine" to app.engineDescription,
                )
        }
    } catch (e: KalinBindException) {
        System.err.println("kalin-server: ${e.message}")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted { app.stop() })
}
