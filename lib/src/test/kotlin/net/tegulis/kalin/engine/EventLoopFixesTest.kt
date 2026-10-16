package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.net.Socket
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
            }
        }
    })
