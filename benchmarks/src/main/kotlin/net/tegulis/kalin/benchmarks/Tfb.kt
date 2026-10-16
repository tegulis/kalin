package net.tegulis.kalin.benchmarks

import kotlinx.serialization.Serializable
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.HandlerExecution
import net.tegulis.kalin.engine.Transport
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.nonBlocking

@Serializable data class Message(val message: String)

/**
 * TechEmpower Framework Benchmarks style app: `/plaintext` and `/json`.
 *
 * Arguments: `engine=LOOM|EVENT_LOOP transport=AUTO|NIO|EPOLL execution=VIRTUAL_THREAD|EVENT_LOOP port=8080 loops=N`
 */
fun main(args: Array<String>) {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val helloBytes = "Hello, World!".toByteArray()
    Kalin.create { config ->
            config.server.port = options["port"]?.toInt() ?: 8080
            config.server.engine = Engine.valueOf(options["engine"] ?: "EVENT_LOOP")
            config.server.transport = Transport.valueOf(options["transport"] ?: "AUTO")
            config.server.handlerExecution = HandlerExecution.valueOf(options["execution"] ?: "VIRTUAL_THREAD")
            options["loops"]?.let { config.server.eventLoops = it.toInt() }
            options["reuseport"]?.let { config.server.reusePort = it.toBoolean() }
            config.server.serverHeader = "Kalin"
            config.http.compressionStrategy = net.tegulis.kalin.compression.CompressionStrategy.NONE
            config.routes.get(
                "/plaintext",
                nonBlocking { ctx -> ctx.contentType(ContentType.PLAIN).result(helloBytes) },
            )
            config.routes.get("/json", nonBlocking { ctx -> ctx.json(Message("Hello, World!")) })
            config.routes.get("/blocking") { ctx -> ctx.contentType(ContentType.PLAIN).result(helloBytes) }
        }
        .start()
}
