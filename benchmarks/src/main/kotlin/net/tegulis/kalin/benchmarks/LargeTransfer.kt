package net.tegulis.kalin.benchmarks

import net.tegulis.kalin.Kalin
import net.tegulis.kalin.engine.Engine
import net.tegulis.kalin.engine.Transport

/** Streams large uploads and downloads (`POST /upload`, `GET /download?mb=N`); run with a small heap. */
fun main(args: Array<String>) {
    val options = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
    Kalin.create { config ->
            config.server.port = options["port"]?.toInt() ?: 8091
            config.server.engine = Engine.valueOf(options["engine"] ?: "LOOM")
            config.server.transport = Transport.valueOf(options["transport"] ?: "AUTO")
            config.server.eventLoops = 2
            config.routes.post("/upload") { ctx ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                val input = ctx.bodyInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                ctx.result(total.toString())
            }
            config.routes.get("/download") { ctx ->
                val mb = ctx.queryParamAsClass<Int>("mb").getOrDefault(1024)
                ctx.disableCompression()
                val out = ctx.outputStream()
                val chunk = ByteArray(1024 * 1024) { (it % 251).toByte() }
                repeat(mb) { out.write(chunk) }
            }
        }
        .start()
}
