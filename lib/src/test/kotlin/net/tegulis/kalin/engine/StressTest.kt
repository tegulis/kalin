package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.http.nonBlocking

/** Many concurrent keep-alive connections with random pipelining; every response is checked. */
class StressTest :
    FunSpec({
        val combos =
            listOf(
                Triple(Engine.LOOM, Transport.NIO, HandlerExecution.VIRTUAL_THREAD),
                Triple(Engine.EVENT_LOOP, Transport.NIO, HandlerExecution.VIRTUAL_THREAD),
                Triple(Engine.EVENT_LOOP, Transport.EPOLL, HandlerExecution.VIRTUAL_THREAD),
                Triple(Engine.EVENT_LOOP, Transport.EPOLL, HandlerExecution.EVENT_LOOP),
            )
        for ((engine, transport, execution) in
            combos.filter { it.second != Transport.EPOLL || net.tegulis.kalin.engine.epoll.Epoll.available() }) {
            test("$engine/$transport/$execution: concurrent pipelined keep-alive clients") {
                val app = Kalin.create { config ->
                    config.startup.showBanner = false
                    config.server.port = 0
                    config.server.engine = engine
                    config.server.transport = transport
                    config.server.handlerExecution = execution
                    config.server.eventLoops = 3
                    config.routes.get("/echo/{n}") { ctx ->
                        if (ctx.pathParam("n").toInt() % 7 == 0) Thread.yield()
                        ctx.result("n=" + ctx.pathParam("n"))
                    }
                    config.routes.get("/fast/{n}", nonBlocking { ctx -> ctx.result("f=" + ctx.pathParam("n")) })
                    config.routes.post("/body") { ctx -> ctx.result(ctx.body().length.toString()) }
                }
                app.start()
                val failures = AtomicInteger()
                val done = AtomicInteger()
                try {
                    Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                        repeat(64) { client ->
                            executor.submit {
                                val random = Random(client)
                                try {
                                    Socket("127.0.0.1", app.port()).use { socket ->
                                        socket.soTimeout = 20_000
                                        socket.tcpNoDelay = true
                                        val input = BufferedInputStream(socket.getInputStream())
                                        val output = socket.getOutputStream()
                                        var sent = 0
                                        while (sent < 300) {
                                            val batch = random.nextInt(1, 6)
                                            val expected = ArrayList<String>()
                                            val request = StringBuilder()
                                            repeat(batch) {
                                                val n = sent++
                                                when (random.nextInt(3)) {
                                                    0 -> {
                                                        request.append("GET /echo/$n HTTP/1.1\r\nHost: x\r\n\r\n")
                                                        expected.add("n=$n")
                                                    }
                                                    1 -> {
                                                        request.append("GET /fast/$n HTTP/1.1\r\nHost: x\r\n\r\n")
                                                        expected.add("f=$n")
                                                    }
                                                    else -> {
                                                        val body = "b".repeat(random.nextInt(0, 3000))
                                                        request.append(
                                                            "POST /body HTTP/1.1\r\nHost: x\r\nContent-Length: ${body.length}\r\n\r\n$body"
                                                        )
                                                        expected.add(body.length.toString())
                                                    }
                                                }
                                            }
                                            output.write(request.toString().toByteArray())
                                            output.flush()
                                            for (e in expected) {
                                                val body = readResponseBody(input)
                                                if (body != e) {
                                                    failures.incrementAndGet()
                                                    throw IllegalStateException("expected $e got $body")
                                                }
                                            }
                                        }
                                    }
                                    done.incrementAndGet()
                                } catch (t: Throwable) {
                                    failures.incrementAndGet()
                                    t.printStackTrace()
                                }
                            }
                        }
                        executor.shutdown()
                        executor.awaitTermination(120, TimeUnit.SECONDS) shouldBe true
                    }
                } finally {
                    app.stop()
                }
                failures.get() shouldBe 0
                done.get() shouldBe 64
            }
        }
    })

/** Reads one fixed-length response and returns its body. */
internal fun readResponseBody(input: InputStream): String {
    var contentLength = -1
    while (true) {
        val line = readLine(input)
        if (line.isEmpty()) break
        if (line.startsWith("Content-Length:", ignoreCase = true))
            contentLength = line.substringAfter(':').trim().toInt()
    }
    check(contentLength >= 0) { "no content length" }
    return String(input.readNBytes(contentLength))
}

private fun readLine(input: InputStream): String {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        check(b >= 0) { "unexpected EOF" }
        if (b == '\n'.code) break
        if (b != '\r'.code) out.write(b)
    }
    return out.toString()
}
