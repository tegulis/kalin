package net.tegulis.kalin.benchmarks

import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.engine.Engine

/** Measures startup time (JVM start to first response) and idle heap with one route. */
fun main(args: Array<String>) {
    val engine = Engine.valueOf(args.firstOrNull() ?: "LOOM")
    val jvmStart = ManagementFactory.getRuntimeMXBean().startTime
    val t0 = System.nanoTime()
    val app =
        Kalin.create { config ->
                config.server.port = 0
                config.server.engine = engine
                config.server.eventLoops = 2
                config.startup.showBanner = false
                config.routes.get("/") { ctx -> ctx.result("Hello World") }
            }
            .start()
    val started = (System.nanoTime() - t0) / 1_000_000
    val client = HttpClient.newHttpClient()
    val body =
        client
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:${app.port()}/")).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            .body()
    val firstResponse = System.currentTimeMillis() - jvmStart
    client.close()
    repeat(3) {
        System.gc()
        Thread.sleep(100)
    }
    val rt = Runtime.getRuntime()
    val used = (rt.totalMemory() - rt.freeMemory()) / 1024.0 / 1024.0
    println(
        "engine=$engine body=$body kalinStart=${started}ms jvmStartToFirstResponse=${firstResponse}ms heapUsedAfterGc=${"%.1f".format(used)}MB"
    )
    app.stop()
}
