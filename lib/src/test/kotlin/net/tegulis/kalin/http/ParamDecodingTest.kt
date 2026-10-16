package net.tegulis.kalin.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.tegulis.kalin.testtools.testKalin

class ParamDecodingTest :
    FunSpec({
        test("invalid percent escapes in params are a bad request") {
            testKalin {
                app {
                    routes.get("/q") { ctx -> ctx.result("${ctx.queryParamMap()}") }
                    routes.get("/ignore") { ctx -> ctx.result("ok") }
                    routes.post("/form") { ctx -> ctx.result("${ctx.formParamMap()}") }
                }
                client.get("/q?a=1&b=%ZZ").code shouldBe 400
                client.get("/q?a=1&%zz=2").code shouldBe 400
                client.get("/q?a=%41").bodyAsText() shouldBe "{a=[A]}"
                client.get("/ignore?b=%ZZ").bodyAsText() shouldBe "ok"
                client.post("/form") { body("a=1&b=%E".toByteArray(), ContentType.FORM_URLENCODED) }.code shouldBe 400
            }
        }
    })
