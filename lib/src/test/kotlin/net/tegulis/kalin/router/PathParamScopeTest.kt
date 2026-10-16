package net.tegulis.kalin.router

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.tegulis.kalin.testtools.testKalin

class PathParamScopeTest :
    FunSpec({
        test("each handler sees only the path params of its own path") {
            testKalin {
                app {
                    routes.before("/users/{id}") { it.header("X-Before", it.pathParamMap().toString()) }
                    routes.get("/users/me") {
                        it.result("me ${it.pathParamMap()} ${runCatching { it.pathParam("id") }.isFailure}")
                    }
                    routes.get("/items/{item}") { it.result("item ${it.pathParam("item")}") }
                    routes.after { it.header("X-After", it.pathParamMap().toString()) }
                    routes.after("/items/{name}") { it.header("X-After-Items", it.pathParamMap().toString()) }
                }
                client.get("/users/me").let {
                    it.header("X-Before") shouldBe "{id=me}"
                    it.bodyAsText() shouldBe "me {} true"
                    it.header("X-After") shouldBe "{}"
                }
                client.get("/items/42").let {
                    it.bodyAsText() shouldBe "item 42"
                    it.header("X-After") shouldBe "{}"
                    it.header("X-After-Items") shouldBe "{name=42}"
                }
            }
        }
    })
