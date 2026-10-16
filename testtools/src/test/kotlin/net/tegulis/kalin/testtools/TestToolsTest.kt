package net.tegulis.kalin.testtools

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.Serializable
import net.tegulis.kalin.Kalin
import net.tegulis.kalin.http.ContentType
import net.tegulis.kalin.http.HttpStatus

@Serializable data class Pet(val name: String, val age: Int)

class TestToolsTest :
    FunSpec({
        for (mode in TestMode.entries) {
            context("$mode mode") {
                test("requests with headers, query params, cookies, bodies and JSON") {
                    testKalin(mode = mode) {
                        app {
                            routes.post("/echo") { ctx ->
                                ctx.header("X-Seen", ctx.header("X-Custom") ?: "none")
                                ctx.cookie("out", "cookie")
                                ctx.result("${ctx.queryParam("q")}|${ctx.cookie("in")}|${ctx.body()}")
                            }
                            routes.post("/pet") { ctx -> ctx.json(ctx.bodyAsClass<Pet>().copy(age = 2)) }
                            routes.post("/form") { ctx -> ctx.result(ctx.formParamMap().toString()) }
                            routes.post("/upload") { ctx -> ctx.result(ctx.uploadedFile("f")!!.filename()) }
                        }
                        val r =
                            client.post("/echo") {
                                header("X-Custom", "yes")
                                queryParam("q", "a b")
                                cookie("in", "c1")
                                body("payload")
                            }
                        r.status shouldBe HttpStatus.OK
                        r.code() shouldBe 200
                        r.bodyAsText() shouldBe "a b|c1|payload"
                        r.body.string() shouldBe "a b|c1|payload"
                        r.header("x-seen") shouldBe "yes"
                        r.cookies["out"] shouldBe "cookie"
                        r.contentType()!!.withoutParameters() shouldBe ContentType.TEXT_PLAIN
                        client.post("/pet") { json(Pet("rex", 1)) }.body<Pet>() shouldBe Pet("rex", 2)
                        client.post("/form") { form("a" to "1", "b" to "2") }.bodyAsText() shouldBe "{a=[1], b=[2]}"
                        client
                            .post("/upload") {
                                multipart(files = listOf(MultipartFile("f", "x.txt", "data".toByteArray())))
                            }
                            .bodyAsText() shouldBe "x.txt"
                        if (mode == TestMode.REAL) server.port shouldBe server.app.port() else server.port shouldBe 0
                        client.origin shouldStartWith "http://"
                    }
                }

                test("raw requests and SSE") {
                    testKalin(mode = mode) {
                        app {
                            routes.get("/") { it.result("raw") }
                            routes.sse("/sse") { it.sendEvent("e", "d", "1") }
                        }
                        val raw =
                            client.raw(
                                "GET / HTTP/1.1\r\nHost: x\r\n\r\nGET / HTTP/1.1\r\nHost: x\r\n\r\n",
                                responses = 2,
                            )
                        raw.responses.map { it.bodyAsText() } shouldBe listOf("raw", "raw")
                        raw.text shouldContain "HTTP/1.1 200 OK"
                        client.sse("/sse") { take(1) shouldBe listOf(SseEvent("e", "d", "1")) }
                    }
                }

                test("default headers") {
                    testKalin(mode = mode) {
                        app { routes.get("/") { it.result(it.header("Authorization") ?: "none") } }
                        client.defaultHeaders["Authorization"] = "Bearer t"
                        client.get("/").bodyAsText() shouldBe "Bearer t"
                    }
                }
            }
        }

        test("KalinTest.test with an existing app") {
            val app = Kalin.create { it.routes.get("/api/v1/ping") { ctx -> ctx.result("PONG") } }
            KalinTest.test(app) { server, client ->
                client.get("/api/v1/ping").code shouldBe 200
                server.isStarted shouldBe true
            }
            app.isStarted shouldBe false
        }

        test("app(kalin) uses a prebuilt app") {
            val prebuilt = Kalin.create { it.routes.get("/") { ctx -> ctx.result("prebuilt") } }
            testKalin {
                app(prebuilt)
                client.get("/").bodyAsText() shouldBe "prebuilt"
            }
        }
    })
