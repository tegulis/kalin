package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import net.tegulis.kalin.config.ServerConfig
import net.tegulis.kalin.http.nonBlocking
import net.tegulis.kalin.testtools.TestMode
import net.tegulis.kalin.testtools.testKalin

class EventLoopFixesTest :
    FunSpec({
        val transports =
            listOf(Transport.NIO, Transport.EPOLL).filter {
                it != Transport.EPOLL || net.tegulis.kalin.engine.epoll.Epoll.available()
            }

        for (transport in transports) {
            context("EVENT_LOOP/$transport") {
                test("connections above maxConnections get 503 with REJECT_503") {
                    testKalin(mode = TestMode.REAL, engine = Engine.EVENT_LOOP, transport = transport) {
                        app {
                            server.maxConnections = 1
                            server.overloadPolicy = OverloadPolicy.REJECT_503
                            routes.get("/") { it.result("ok") }
                        }
                        Socket("127.0.0.1", server.port).use { first ->
                            first.soTimeout = 5000
                            first.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            readResponseBody(first.getInputStream().buffered()) shouldBe "ok"
                            Socket("127.0.0.1", server.port).use { second ->
                                second.soTimeout = 5000
                                String(second.getInputStream().readAllBytes()) shouldStartWith
                                    "HTTP/1.1 503 Service Unavailable\r\n"
                            }
                        }
                    }
                }

                test("a file that shrank after its length was taken fails fast") {
                    val file = sparseFile(100_000)
                    withEngine(
                        transport,
                        { ex ->
                            try {
                                ex.sendFile(file, 0, 300_000)
                            } catch (_: IOException) {
                                ex.abort()
                            }
                        },
                    ) {
                        Socket("127.0.0.1", port).use { s ->
                            s.soTimeout = 5000
                            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            // The connection closes at the end of the file instead of stalling until the write timeout
                            val received = s.getInputStream().readAllBytes()
                            String(received, 0, 12, Charsets.ISO_8859_1) shouldBe "HTTP/1.1 200"
                            (received.size < 300_000) shouldBe true
                        }
                    }
                    Files.delete(file)
                }

                test("a slow file download that keeps progressing outlives the write timeout") {
                    val size = 24L * 1024 * 1024
                    val file = sparseFile(size)
                    val failure = AtomicReference<Throwable>()
                    withEngine(
                        transport,
                        { ex ->
                            try {
                                ex.sendFile(file, 0, size)
                            } catch (e: IOException) {
                                failure.set(e)
                                ex.abort()
                            }
                        },
                        configure = { writeTimeout = 300.milliseconds },
                    ) {
                        Socket().use { s ->
                            s.receiveBufferSize = 64 * 1024
                            s.connect(InetSocketAddress("127.0.0.1", port))
                            s.soTimeout = 5000
                            s.getOutputStream()
                                .write("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
                            // Seconds in total, far longer than the write timeout, but always progressing
                            val input = s.getInputStream()
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                Thread.sleep(10)
                            }
                            (total > size) shouldBe true
                        }
                    }
                    failure.get() shouldBe null
                    Files.delete(file)
                }

                test("a non-blocking handler sends a file larger than the heap without copying it") {
                    val size = Runtime.getRuntime().maxMemory() + 64L * 1024 * 1024
                    val file = sparseFile(size)
                    val failure = AtomicReference<Throwable>()
                    val onLoop = AtomicBoolean(false)
                    withEngine(
                        transport,
                        { ex ->
                            onLoop.set(!Thread.currentThread().isVirtual)
                            try {
                                ex.sendFile(file, 0, size)
                            } catch (t: Throwable) {
                                failure.set(t)
                                ex.abort()
                            }
                        },
                        nonBlocking = true,
                    ) {
                        Socket("127.0.0.1", port).use { s ->
                            s.soTimeout = 5000
                            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                            val head = String(s.getInputStream().readNBytes(1024 * 1024), Charsets.ISO_8859_1)
                            head shouldStartWith "HTTP/1.1 200"
                            head shouldContain "Content-Length: $size"
                        }
                    }
                    onLoop.get() shouldBe true
                    failure.get() shouldBe null
                    Files.delete(file)
                }

                test("X-HTTP-Method-Override never runs a blocking handler on the loop") {
                    testKalin(mode = TestMode.REAL, engine = Engine.EVENT_LOOP, transport = transport) {
                        app {
                            routes.post("/m", nonBlocking { it.result("post") })
                            routes.put("/m") { it.result(if (Thread.currentThread().isVirtual) "virtual" else "loop") }
                        }
                        client.post("/m") { header("X-HTTP-Method-Override", "PUT") }.bodyAsText() shouldBe "virtual"
                        client.post("/m").bodyAsText() shouldBe "post"
                    }
                }
            }
        }
    })

/** A bare event loop engine (one loop) under test. */
private class EngineUnderTest(val port: Int, private val engine: EventLoopEngine) {
    private val stopped = AtomicBoolean(false)

    fun stop() {
        if (stopped.compareAndSet(false, true)) engine.stop()
    }
}

/** Runs [block] against a bare event loop engine whose exchanges go to [handle]. */
private fun withEngine(
    transport: Transport,
    handle: (Http1Exchange) -> Unit,
    nonBlocking: Boolean = false,
    configure: ServerConfig.() -> Unit = {},
    block: EngineUnderTest.() -> Unit,
) {
    val config =
        ServerConfig().apply {
            port = 0
            host = "127.0.0.1"
            eventLoops = 1
            configure()
        }
    val handler =
        object : ExchangeHandler {
            override fun handle(exchange: Http1Exchange) = handle(exchange)

            override fun isNonBlocking(method: String, path: String): Boolean = nonBlocking
        }
    val engine = EventLoopEngine(ServerRuntime(config, handler), epoll = transport == Transport.EPOLL)
    val underTest = EngineUnderTest(engine.start().port, engine)
    try {
        underTest.block()
    } finally {
        underTest.stop()
    }
}

private fun sparseFile(size: Long): Path {
    val file = Files.createTempFile("kalin-file", ".bin")
    RandomAccessFile(file.toFile(), "rw").use { it.setLength(size) }
    return file
}

/** Sends [bytes] every 150 ms for up to [millis]; returns true once the server closed the connection. */
private fun dripUntilClosed(socket: Socket, bytes: ByteArray, millis: Long): Boolean {
    val end = System.nanoTime() + millis * 1_000_000
    socket.soTimeout = 150
    val buf = ByteArray(1024)
    while (System.nanoTime() < end) {
        try {
            socket.getOutputStream().write(bytes)
            if (socket.getInputStream().read(buf) < 0) return true
        } catch (_: SocketTimeoutException) {} catch (_: IOException) {
            return true
        }
    }
    return false
}
