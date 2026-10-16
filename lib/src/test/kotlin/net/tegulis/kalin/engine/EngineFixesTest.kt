package net.tegulis.kalin.engine

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
    })
