package net.tegulis.kalin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.tegulis.kalin.config.KalinConfig
import net.tegulis.kalin.http.Context
import net.tegulis.kalin.http.HttpStatus
import net.tegulis.kalin.ktx.handler
import net.tegulis.kalin.ktx.header
import net.tegulis.kalin.ktx.pathParam
import net.tegulis.kalin.ktx.queryParam
import net.tegulis.kalin.ktx.respondJson
import net.tegulis.kalin.ktx.respondStatus
import net.tegulis.kalin.ktx.respondText
import net.tegulis.kalin.testtools.TestClient
import net.tegulis.kalin.testtools.testKalin

class Greeter(private val greeting: String) {
    fun greet(name: String) = "$greeting, $name"
}

/** Routes using an implicit service from a context parameter. */
context(greeter: Greeter)
fun KalinConfig.greetingRoutes() {
    routes.get("/greet/{name}", handler { respondText(greeter.greet(pathParam("name"))) })
}

/** A request-scoped helper combining contexts. */
context(ctx: Context, greeter: Greeter)
fun politeGreeting(): String = greeter.greet(ctx.queryParam("name") ?: "stranger") + "!"

/** A test helper using the client from the testKalin context. */
context(client: TestClient)
fun fetch(path: String): String = client.get(path).bodyAsText()

class KtxTest :
    FunSpec({
        test("context-parameter handlers and helpers") {
            testKalin {
                app {
                    context(Greeter("Hello")) {
                        greetingRoutes()
                        routes.get("/polite", handler { respondText(politeGreeting()) })
                    }
                    routes.get("/info", handler { respondJson(mapOf("q" to queryParam("q"), "h" to header("X-H"))) })
                    routes.get("/teapot", handler { respondStatus(HttpStatus.IM_A_TEAPOT) })
                }
                fetch("/greet/kalin") shouldBe "Hello, kalin"
                fetch("/polite?name=you") shouldBe "Hello, you!"
                client.get("/info?q=1") { header("X-H", "h") }.bodyAsText() shouldBe """{"q":"1","h":"h"}"""
                client.get("/teapot").code shouldBe 418
            }
        }
    })
