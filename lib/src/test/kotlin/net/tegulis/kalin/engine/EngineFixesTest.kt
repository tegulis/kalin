package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.testtools.testKalin

class EngineFixesTest :
    FunSpec({
        test("Expect: 100-continue without a body keeps the connection alive") {
            testKalin {
                app { routes.get("/") { it.result("root") } }
                val r =
                    client.raw(
                        "GET / HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n",
                        responses = 2,
                    )
                r.responses.size shouldBe 2
                r.responses[0].header("Connection") shouldBe null
                r.responses[1].bodyAsText() shouldBe "root"
            }
        }

        test("sendFile of a missing file fails before the response is committed") {
            testKalin {
                app {
                    routes.get("/") { it.result("root") }
                    routes.get("/missing") { ctx ->
                        try {
                            ctx.exchange().sendFile(Path.of("/nonexistent/kalin-missing.txt"), 0, 10)
                        } catch (_: NoSuchFileException) {
                            ctx.status(404).result("missing")
                        }
                    }
                }
                val r =
                    client.raw(
                        "GET /missing HTTP/1.1\r\nHost: x\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n",
                        responses = 2,
                    )
                r.responses.size shouldBe 2
                r.responses[0].code shouldBe 404
                r.responses[0].bodyAsText() shouldBe "missing"
                r.responses[1].bodyAsText() shouldBe "root"
            }
        }

        test("responses are flushed when the next pipelined request is incomplete") {
            testKalin {
                app {
                    server.idleTimeout = 60.seconds
                    server.requestTimeout = 60.seconds
                    routes.get("/") { it.result("root") }
                    routes.post("/echo") { it.result(it.body()) }
                }
                val trailingCrlf =
                    client.raw(
                        "POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nhello\r\n",
                        timeout = 3.seconds,
                    )
                trailingCrlf.responses.size shouldBe 1
                trailingCrlf.response.bodyAsText() shouldBe "hello"
                val partial = client.raw("GET / HTTP/1.1\r\nHost: x\r\n\r\nGET / HTTP/1.1\r\nHo", timeout = 3.seconds)
                partial.responses.size shouldBe 1
                partial.response.bodyAsText() shouldBe "root"
            }
        }
    })
