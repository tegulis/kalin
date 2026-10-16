package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.KalinInternalApi

/** Closing connections without losing responses: on shutdown and after `Connection: close`. */
@OptIn(KalinInternalApi::class)
class GracefulCloseTest :
    FunSpec({
        test("responses written during shutdown announce Connection: close") {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val app = Kalin.create { config ->
                config.startup.showBanner = false
                config.server.transport = Transport.MEMORY
                config.routes.get("/slow") {
                    entered.countDown()
                    release.await(10, TimeUnit.SECONDS)
                    it.result("done")
                }
            }
            app.start()
            var stopper: Thread? = null
            try {
                app.connectInMemory().use { conn ->
                    conn.output.write("GET /slow HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    conn.output.flush()
                    entered.await(10, TimeUnit.SECONDS) shouldBe true
                    stopper = Thread.ofVirtual().start { app.stop() }
                    Thread.sleep(200)
                    release.countDown()
                    val response = String(conn.input.readAllBytes())
                    response shouldContain "Connection: close"
                    response.endsWith("done") shouldBe true
                }
            } finally {
                release.countDown()
                stopper?.join(10_000) ?: app.stop()
            }
        }

        test("Connection: close without a body still lingers over pipelined input (LOOM)") {
            val size = 4 * 1024 * 1024
            val app = Kalin.create { config ->
                config.startup.showBanner = false
                config.server.engine = Engine.LOOM
                config.server.transport = Transport.NIO
                config.server.port = 0
                config.server.host = "127.0.0.1"
                config.routes.get("/big") { it.result(ByteArray(size)) }
            }
            app.start()
            try {
                repeat(3) {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", app.port()))
                        socket.soTimeout = 10_000
                        val junk = "GET /big HTTP/1.1\r\nHost: x\r\n\r\n".repeat(3000)
                        Thread.ofVirtual().start {
                            try {
                                socket
                                    .getOutputStream()
                                    .write(
                                        ("GET /big HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n$junk").toByteArray()
                                    )
                            } catch (_: java.io.IOException) {}
                        }
                        Thread.sleep(200) // let the response and the close happen before reading
                        val all = socket.getInputStream().readAllBytes()
                        val headEnd = String(all, 0, minOf(all.size, 1024)).indexOf("\r\n\r\n")
                        (all.size - headEnd - 4) shouldBe size
                    }
                }
            } finally {
                app.stop()
            }
        }
    })
