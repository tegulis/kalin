package net.tegulis.kalin.router

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.tegulis.kalin.http.BadRequestResponse
import net.tegulis.kalin.testtools.testKalin

class ErrorResponseTest :
    FunSpec({
        test("default error answers to HTML clients are plain text") {
            testKalin {
                app {
                    routes.get("/") { ctx ->
                        throw BadRequestResponse("<script>alert(${ctx.queryParam("x")})</script>")
                    }
                    routes.get("/typed") { ctx ->
                        ctx.contentType("text/html")
                        throw BadRequestResponse("<b>bad</b>")
                    }
                }
                client
                    .get("/?x=1") { header("Accept", "text/html") }
                    .let {
                        it.code shouldBe 400
                        it.contentType()!!.mimeType shouldBe "text/plain"
                        it.bodyAsText() shouldBe "<script>alert(1)</script>"
                    }
                client.get("/typed").contentType()!!.mimeType shouldBe "text/plain"
                client.get("/missing<b>x</b>") { header("Accept", "text/html") }.contentType()!!.mimeType shouldBe
                    "text/plain"
            }
        }

        test("htmlErrorMessages sends HTML messages as HTML") {
            testKalin {
                app {
                    http.htmlErrorMessages = true
                    routes.get("/html") { throw BadRequestResponse("<b>bad</b>") }
                    routes.get("/text") { throw BadRequestResponse("1 < 2") }
                }
                client
                    .get("/html") { header("Accept", "text/html") }
                    .let {
                        it.contentType()!!.mimeType shouldBe "text/html"
                        it.bodyAsText() shouldBe "<b>bad</b>"
                    }
                client.get("/html").contentType()!!.mimeType shouldBe "text/plain"
                client.get("/text") { header("Accept", "text/html") }.contentType()!!.mimeType shouldBe "text/plain"
                client.get("/html") { header("Accept", "application/json") }.contentType()!!.mimeType shouldBe
                    "application/json"
                client.get("/missing<b>x</b>") { header("Accept", "text/html") }.contentType()!!.mimeType shouldBe
                    "text/plain"
            }
        }
    })
