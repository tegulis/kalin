package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.testtools.testKalin

class ProtocolTest :
    FunSpec({
        val setup: KalinConfig.() -> Unit = {
            routes.get("/") { ctx -> ctx.result("root") }
            routes.get("/n/{n}") { ctx -> ctx.result("n=" + ctx.pathParam("n")) }
            routes.post("/echo") { ctx -> ctx.result(ctx.body()) }
            routes.post("/ignore") { ctx -> ctx.result("ignored") }
            routes.post("/stream-len") { ctx ->
                var total = 0L
                val buf = ByteArray(65536)
                val input = ctx.bodyInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                }
                ctx.result(total.toString())
            }
            routes.get("/big") { ctx -> ctx.result("x".repeat(200_000)) }
            routes.get("/chunked") { ctx ->
                val out = ctx.outputStream()
                repeat(100) { out.write(("line $it\n".repeat(1000)).toByteArray()) }
            }
        }

        test("pipelined requests are answered in order") {
            testKalin {
                app(setup)
                val req = (1..20).joinToString("") { "GET /n/$it HTTP/1.1\r\nHost: x\r\n\r\n" }
                val result = client.raw(req, responses = 20)
                result.responses.size shouldBe 20
                result.responses.mapIndexed { i, r -> r.bodyAsText() shouldBe "n=${i + 1}" }
            }
        }

        test("keep-alive and connection close") {
            testKalin {
                app(setup)
                val result =
                    client.raw(
                        "GET / HTTP/1.1\r\nHost: x\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n",
                        responses = 3,
                    )
                result.responses.size shouldBe 2
                result.responses[1].header("Connection") shouldBe "close"
                result.closedByServer shouldBe true
            }
        }

        test("HTTP/1.0 gets close-delimited or fixed responses, keep-alive on request") {
            testKalin {
                app(setup)
                val r1 = client.raw("GET / HTTP/1.0\r\n\r\n")
                r1.response.bodyAsText() shouldBe "root"
                r1.text shouldContain "Connection: close"
                val r2 =
                    client.raw("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\nGET / HTTP/1.0\r\n\r\n", responses = 2)
                r2.responses.size shouldBe 2
                r2.text shouldContain "Connection: keep-alive"
                val r3 = client.raw("GET /chunked HTTP/1.0\r\n\r\n")
                r3.text shouldNotContain "Transfer-Encoding"
                r3.response.bodyAsText().length shouldBe (0 until 100).sumOf { "line $it\n".length * 1000 }
            }
        }

        test("chunked request bodies") {
            testKalin {
                app(setup)
                val r =
                    client.raw(
                        "POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n" +
                            "GET / HTTP/1.1\r\nHost: x\r\n\r\n",
                        responses = 2,
                    )
                r.responses[0].bodyAsText() shouldBe "hello world"
                r.responses[1].bodyAsText() shouldBe "root"
            }
        }

        test("unread request bodies are drained to keep the connection alive") {
            testKalin {
                app(setup)
                val r =
                    client.raw(
                        "POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 10\r\n\r\n0123456789GET / HTTP/1.1\r\nHost: x\r\n\r\n",
                        responses = 2,
                    )
                r.responses.map { it.bodyAsText() } shouldBe listOf("ignored", "root")
            }
        }

        test("100-continue is sent only when the body is read") {
            testKalin {
                app(setup)
                val read =
                    client.raw("POST /echo HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\nhi")
                read.text shouldContain "HTTP/1.1 100 Continue"
                read.response.bodyAsText() shouldBe "hi"
                val ignored =
                    client.raw("POST /ignore HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\nExpect: 100-continue\r\n\r\n")
                ignored.text shouldNotContain "100 Continue"
                ignored.response.bodyAsText() shouldBe "ignored"
                ignored.response.header("Connection") shouldBe "close"
            }
        }

        test("protocol errors are answered and the connection closed") {
            testKalin {
                app(setup)
                client.raw("GET / HTTP/1.1\r\n\r\n").response.code shouldBe 400
                client.raw("GET / HTTP/2.0\r\nHost: x\r\n\r\n").response.code shouldBe 505
                client.raw("POST / HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: gzip\r\n\r\n").response.code shouldBe 501
                client
                    .raw("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n")
                    .response
                    .code shouldBe 400
                client.raw("GET /" + "a".repeat(9000) + " HTTP/1.1\r\nHost: x\r\n\r\n").response.code shouldBe 414
                client.raw("GET / HTTP/1.1\r\nHost: x\r\nX: " + "a".repeat(17000) + "\r\n\r\n").response.code shouldBe
                    431
                val bad =
                    client.raw(
                        "GET / HTTP/1.1\r\nHost: x\r\n folded\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n",
                        responses = 2,
                    )
                bad.responses.size shouldBe 1
                bad.response.code shouldBe 400
                bad.closedByServer shouldBe true
            }
        }

        test("malformed chunked body gives 400") {
            testKalin {
                app(setup)
                client
                    .raw("POST /echo HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n")
                    .response
                    .code shouldBe 400
            }
        }

        test("large request and response bodies stream") {
            testKalin {
                app(setup)
                val size = 20 * 1024 * 1024
                val r = client.post("/stream-len") { body(ByteArray(size) { (it % 251).toByte() }) }
                r.bodyAsText() shouldBe size.toString()
                client.get("/big").bodyAsText().length shouldBe 200_000
                val chunked = client.get("/chunked")
                chunked.bodyAsText().lines().size shouldBe 100_001
            }
        }

        test("HEAD returns headers of GET without body") {
            testKalin {
                app(setup)
                val r =
                    client.raw("HEAD /big HTTP/1.1\r\nHost: x\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n", responses = 2)
                r.responses[0].header("Content-Length") shouldBe "200000"
                r.responses[0].bodyAsBytes().size shouldBe 0
                r.responses[1].bodyAsText() shouldBe "root"
            }
        }

        test("idle and slow (slowloris) connections are closed by timeouts") {
            testKalin {
                app {
                    setup(this)
                    server.requestTimeout = 300.milliseconds
                    server.idleTimeout = 300.milliseconds
                }
                val start = System.nanoTime()
                val r = client.raw("GET / HTTP/1.1\r\nHost: x\r\n", timeout = 5.seconds)
                r.closedByServer shouldBe true
                ((System.nanoTime() - start) / 1_000_000 < 3000) shouldBe true
                val idle = client.raw("GET / HTTP/1.1\r\nHost: x\r\n\r\n", responses = 2, timeout = 5.seconds)
                idle.responses.size shouldBe 1
                idle.closedByServer shouldBe true
            }
        }

        test("response header injection is neutralized") {
            testKalin {
                app { routes.get("/inject") { ctx -> ctx.header("X-Evil", "a\r\nSet-Cookie: pwned=1").result("ok") } }
                val r = client.raw("GET /inject HTTP/1.1\r\nHost: x\r\n\r\n")
                r.response.header("Set-Cookie") shouldBe null
                r.response.header("X-Evil") shouldBe "a  Set-Cookie: pwned=1"
            }
        }

        test("many sequential requests on one connection") {
            testKalin {
                app(setup)
                val out = ByteArrayOutputStream()
                repeat(500) { out.write("GET /n/$it HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()) }
                val r = client.raw(out.toByteArray(), responses = 500)
                r.responses.size shouldBe 500
                r.responses.last().bodyAsText() shouldBe "n=499"
            }
        }
    })
