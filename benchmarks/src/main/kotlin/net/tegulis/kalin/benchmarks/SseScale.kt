package net.tegulis.kalin.benchmarks

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.sse.SseClient

/**
 * SSE scale test server: clients connect to `/events` and stay connected; `/broadcast` sends one event to all; `/stats`
 * reports the number of clients, heap after GC and live threads.
 */
fun main(args: Array<String>) {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    val clients = ConcurrentLinkedQueue<SseClient>()
    Kalin.create { config ->
            config.server.port = options["port"]?.toInt() ?: 8090
            config.server.engine = Engine.valueOf(options["engine"] ?: "EVENT_LOOP")
            config.server.eventLoops = options["loops"]?.toInt() ?: 4
            config.server.idleTimeout = kotlin.time.Duration.parse("10m")
            config.routes.sse("/events") { client ->
                client.keepAlive()
                client.onClose { clients.remove(client) }
                clients.add(client)
                client.sendEvent("hello", "1")
            }
            config.routes.post("/broadcast") { ctx ->
                var n = 0
                for (c in clients) {
                    c.sendEvent("news", "broadcast")
                    n++
                }
                ctx.result(n.toString())
            }
            config.routes.get("/stats") { ctx ->
                repeat(2) { System.gc() }
                val rt = Runtime.getRuntime()
                val heap = (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024
                val threads = ManagementFactory.getThreadMXBean().threadCount
                ctx.result("clients=${clients.size} heapMB=$heap platformThreads=$threads")
            }
        }
        .start()
}
