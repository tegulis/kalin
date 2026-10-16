package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.http.nonBlocking
import net.tegulis.kalin.testtools.TestMode
import net.tegulis.kalin.testtools.testKalin

class EngineMatrixTest :
    FunSpec({
        data class Combo(
            val engine: Engine,
            val transport: Transport,
            val execution: HandlerExecution,
            val expected: String,
        )

        val combos =
            listOf(
                Combo(Engine.LOOM, Transport.NIO, HandlerExecution.VIRTUAL_THREAD, "LOOM/NIO"),
                Combo(Engine.EVENT_LOOP, Transport.NIO, HandlerExecution.VIRTUAL_THREAD, "EVENT_LOOP/NIO"),
                Combo(Engine.EVENT_LOOP, Transport.EPOLL, HandlerExecution.VIRTUAL_THREAD, "EVENT_LOOP/EPOLL"),
                Combo(Engine.EVENT_LOOP, Transport.NIO, HandlerExecution.EVENT_LOOP, "EVENT_LOOP/NIO"),
                Combo(Engine.EVENT_LOOP, Transport.EPOLL, HandlerExecution.EVENT_LOOP, "EVENT_LOOP/EPOLL"),
            )

        for (c in
            combos.filter { it.transport != Transport.EPOLL || net.tegulis.kalin.engine.epoll.Epoll.available() }) {
            context("${c.expected} with ${c.execution} handlers") {
                test("basic requests, pipelining, bodies, errors") {
                    testKalin(mode = TestMode.REAL, engine = c.engine, transport = c.transport) {
                        app {
                            server.handlerExecution = c.execution
                            server.eventLoops = 2
                            routes.get("/") { it.result("hello") }
                            routes.get("/thread") {
                                it.result(if (Thread.currentThread().isVirtual) "virtual" else "loop")
                            }
                            routes.get(
                                "/fast",
                                nonBlocking { it.result(if (Thread.currentThread().isVirtual) "virtual" else "loop") },
                            )
                            routes.post("/echo") { it.result(it.body()) }
                            routes.get("/big") { it.result("y".repeat(3_000_000)) }
                            routes.get("/fail") { throw IllegalStateException("x") }
                        }
                        server.app.engineDescription shouldBe c.expected
                        client.get("/").bodyAsText() shouldBe "hello"
                        val fast = client.get("/fast").bodyAsText()
                        if (c.engine == Engine.EVENT_LOOP) fast shouldBe "loop" else fast shouldBe "virtual"
                        client.get("/thread").bodyAsText() shouldBe
                            if (c.execution == HandlerExecution.EVENT_LOOP && c.engine == Engine.EVENT_LOOP) "loop"
                            else "virtual"
                        client.post("/echo") { body("ping") }.bodyAsText() shouldBe "ping"
                        client.post("/echo") { body("x".repeat(900_000)) }.bodyAsText().length shouldBe 900_000
                        client.get("/big").bodyAsText().length shouldBe 3_000_000
                        client.get("/fail").code shouldBe 500
                        val raw = (1..50).joinToString("") { "GET / HTTP/1.1\r\nHost: x\r\n\r\n" }
                        client.raw(raw, responses = 50).responses.size shouldBe 50
                        client
                            .raw(
                                "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n",
                                responses = 2,
                            )
                            .responses
                            .map { it.bodyAsText() } shouldBe listOf("abc", "hello")
                        client.raw("GET / HTTP/1.1\r\n\r\n").response.code shouldBe 400
                    }
                }

                test("slow readers get backpressure and complete") {
                    testKalin(mode = TestMode.REAL, engine = c.engine, transport = c.transport) {
                        app {
                            server.handlerExecution = c.execution
                            server.eventLoops = 1
                            routes.get("/stream") { ctx ->
                                val out = ctx.outputStream()
                                val chunk = ByteArray(64 * 1024) { 'z'.code.toByte() }
                                repeat(160) { out.write(chunk) } // 10 MB
                            }
                        }
                        Socket("127.0.0.1", server.port).use { socket ->
                            socket
                                .getOutputStream()
                                .write("GET /stream HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                            Thread.sleep(300) // let the server fill the socket buffers
                            val total = countBytes(socket.getInputStream())
                            (total > 10 * 1024 * 1024) shouldBe true
                        }
                    }
                }

                test("SSE disconnect detection") {
                    val closed = CountDownLatch(1)
                    testKalin(mode = TestMode.REAL, engine = c.engine, transport = c.transport) {
                        app {
                            server.handlerExecution = c.execution
                            routes.sse("/sse") { client ->
                                client.keepAlive()
                                client.onClose { closed.countDown() }
                                client.sendEvent("hi", "there")
                            }
                        }
                        client.sse("/sse") { take(1).single().data shouldBe "there" }
                        closed.await(5, TimeUnit.SECONDS) shouldBe true
                    }
                }
            }
        }

        test("epoll falls back to NIO when the engine does not support it") {
            val app = Kalin.create {
                it.startup.showBanner = false
                it.server.port = 0
                it.server.engine = Engine.LOOM
                it.server.transport = Transport.EPOLL
            }
            app.start()
            try {
                app.engineDescription shouldBe "LOOM/NIO"
            } finally {
                app.stop()
            }
        }

        test("graceful stop waits for in-flight requests") {
            for (engine in Engine.entries) {
                val started = CountDownLatch(1)
                val app = Kalin.create {
                    it.startup.showBanner = false
                    it.server.port = 0
                    it.server.engine = engine
                    it.server.eventLoops = 1
                    it.routes.get("/slow") { ctx ->
                        started.countDown()
                        Thread.sleep(500)
                        ctx.result("finished")
                    }
                }
                app.start()
                val result =
                    java.util.concurrent.CompletableFuture.supplyAsync {
                        Socket("127.0.0.1", app.port()).use { s ->
                            s.soTimeout = 5000
                            s.getOutputStream().write("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            String(s.getInputStream().readNBytes(1000))
                        }
                    }
                started.await(5, TimeUnit.SECONDS) shouldBe true
                app.stop()
                result.get(5, TimeUnit.SECONDS).endsWith("finished") shouldBe true
            }
        }
    })

private fun countBytes(input: InputStream): Long {
    val buf = ByteArray(65536)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) return total
        total += n
    }
}

class DeepPipelineTest :
    FunSpec({
        for (execution in HandlerExecution.entries) {
            test("thousands of pipelined requests in one write ($execution)") {
                testKalin(mode = TestMode.REAL, engine = Engine.EVENT_LOOP, transport = Transport.NIO) {
                    app {
                        server.handlerExecution = execution
                        server.eventLoops = 1
                        server.maxRequestLineSize = 64 * 1024
                        routes.get("/", nonBlocking { it.result("x") })
                    }
                    val count = 20_000
                    val request = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".repeat(count)
                    client.raw(request, responses = count, timeout = 60.seconds).responses.size shouldBe count
                }
            }
        }
    })

class LingeringCloseTest :
    FunSpec({
        val combos =
            listOf(
                    Engine.LOOM to Transport.NIO,
                    Engine.EVENT_LOOP to Transport.NIO,
                    Engine.EVENT_LOOP to Transport.EPOLL,
                )
                .filter { it.second != Transport.EPOLL || net.tegulis.kalin.engine.epoll.Epoll.available() }
        for ((engine, transport) in combos) {
            test("$engine/$transport: an early error response survives unread request data") {
                testKalin(mode = TestMode.REAL, engine = engine, transport = transport) {
                    app { routes.post("/") { it.result("ok") } }
                    val body = "x".repeat(200_000)
                    val r = client.raw("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip\r\n\r\n$body")
                    r.response.code shouldBe 501
                }
            }
        }
    })

class IdleStreamTest :
    FunSpec({
        val combos =
            listOf(
                    Engine.LOOM to Transport.NIO,
                    Engine.EVENT_LOOP to Transport.NIO,
                    Engine.EVENT_LOOP to Transport.EPOLL,
                )
                .filter { it.second != Transport.EPOLL || net.tegulis.kalin.engine.epoll.Epoll.available() }
        for ((engine, transport) in combos) {
            test("$engine/$transport: idle SSE streams outlive the write and request timeouts") {
                val clients = java.util.concurrent.ConcurrentLinkedQueue<net.tegulis.kalin.sse.SseClient>()
                testKalin(mode = TestMode.REAL, engine = engine, transport = transport) {
                    app {
                        server.writeTimeout = 200.milliseconds
                        server.requestTimeout = 200.milliseconds
                        routes.sse("/sse") { client ->
                            client.keepAlive()
                            clients.add(client)
                            client.sendEvent("first", "1")
                        }
                    }
                    client.sse("/sse") {
                        take(1).single().data shouldBe "1"
                        Thread.sleep(1500)
                        clients.forEach { it.sendEvent("second", "2") }
                        take(1).single().data shouldBe "2"
                    }
                }
            }
        }
    })

class ActiveCountTest :
    FunSpec({
        for (execution in HandlerExecution.entries) {
            test("the active exchange count returns to zero after pipelined requests ($execution)") {
                val app = Kalin.create {
                    it.startup.showBanner = false
                    it.server.port = 0
                    it.server.engine = Engine.EVENT_LOOP
                    it.server.handlerExecution = execution
                    it.server.eventLoops = 1
                    it.server.shutdownTimeout = 30.seconds
                    it.routes.get("/") { ctx -> ctx.result("x") }
                }
                app.start()
                java.net.Socket("127.0.0.1", app.port()).use { s ->
                    s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".repeat(50).toByteArray())
                    s.soTimeout = 5000
                    val input = java.io.BufferedInputStream(s.getInputStream())
                    repeat(50) { readResponseBody(input) }
                }
                val start = System.nanoTime()
                app.stop()
                ((System.nanoTime() - start) / 1_000_000 < 5_000) shouldBe true
            }
        }
    })
